package kz.aitu.ftr.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Application-level replicated database access.
 * Writes go to primary and are mirrored to standby (dual-write).
 * Reads prefer standby and fall back to primary.
 * If a write to primary fails in FT mode, the standby is promoted and writes continue.
 * In baseline mode only the primary is used: one database failure means total database loss.
 */
public class ReplicatedDatabase implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplicatedDatabase.class);

    private final String primaryUrl;
    private final String standbyUrl;
    private final String user;
    private final String password;
    private final boolean replicationEnabled;
    private volatile boolean standbyHealthy = true;
    private volatile boolean promoted = false;

    public ReplicatedDatabase(String primaryUrl, String standbyUrl,
                              String user, String password, boolean replicationEnabled) {
        this.primaryUrl = primaryUrl;
        this.standbyUrl = standbyUrl;
        this.user = user;
        this.password = password;
        this.replicationEnabled = replicationEnabled;
    }

    public boolean isReplicationEnabled() {
        return replicationEnabled;
    }

    public boolean isPromoted() {
        return promoted;
    }

    public boolean isStandbyHealthy() {
        return standbyHealthy;
    }

    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    public <T> T read(SqlWork<T> work) {
        if (promoted) {
            return execute(standbyUrl, work, "promoted-standby");
        }
        if (replicationEnabled && standbyHealthy) {
            try {
                return execute(standbyUrl, work, "standby");
            } catch (DataAccessException e) {
                log.warn("REPLICA read on standby failed ({}), falling back to primary", e.getMessage());
                standbyHealthy = false;
            }
        }
        return execute(primaryUrl, work, "primary");
    }

    public <T> T write(SqlWork<T> work) {
        if (!replicationEnabled) {
            return execute(primaryUrl, work, "primary");
        }
        if (promoted) {
            return execute(standbyUrl, work, "promoted-standby");
        }
        T result = execute(primaryUrl, work, "primary");
        if (standbyHealthy) {
            try {
                execute(standbyUrl, work, "standby");
            } catch (DataAccessException e) {
                log.warn("REPLICA mirror write to standby failed: {}", e.getMessage());
                standbyHealthy = false;
            }
        }
        return result;
    }

    /**
     * Promotes the standby to be the primary write target after primary failure.
     */
    public synchronized void promoteStandby() {
        if (!replicationEnabled) {
            throw new IllegalStateException("replication disabled (baseline mode)");
        }
        if (promoted) {
            return;
        }
        execute(standbyUrl, c -> {
            try (PreparedStatement st = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS promotion_marker (id int primary key, at timestamptz)")) {
                st.executeUpdate();
            }
            try (PreparedStatement st = c.prepareStatement(
                    "INSERT INTO promotion_marker VALUES (1, now()) ON CONFLICT (id) DO NOTHING")) {
                st.executeUpdate();
            }
            return null;
        }, "standby-promotion");
        promoted = true;
        log.warn("REPLICA standby promoted to primary");
    }

    private <T> T execute(String url, SqlWork<T> work, String label) {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            return work.run(c);
        } catch (SQLException e) {
            throw new DataAccessException(label + " database error: " + e.getMessage(), e);
        }
    }

    public void ensureSchema(String ddl) {
        execute(primaryUrl, c -> {
            try (PreparedStatement st = c.prepareStatement(ddl)) {
                st.executeUpdate();
            }
            return null;
        }, "primary-schema");
        if (replicationEnabled) {
            try {
                execute(standbyUrl, c -> {
                    try (PreparedStatement st = c.prepareStatement(ddl)) {
                        st.executeUpdate();
                    }
                    return null;
                }, "standby-schema");
            } catch (DataAccessException e) {
                log.warn("REPLICA standby schema setup failed: {}", e.getMessage());
            }
        }
    }

    public static class DataAccessException extends RuntimeException {
        public DataAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public void close() {
    }
}
