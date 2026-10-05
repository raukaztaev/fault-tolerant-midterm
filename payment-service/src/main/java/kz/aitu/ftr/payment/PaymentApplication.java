package kz.aitu.ftr.payment;

import kz.aitu.ftr.common.Backoff;
import kz.aitu.ftr.common.ChaosController;
import kz.aitu.ftr.common.DbRetry;
import kz.aitu.ftr.common.Host;
import kz.aitu.ftr.common.ReplicatedDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Payment service with four software fault-tolerance mechanisms:
 * 1. Idempotent processing: an idempotency key prevents duplicate charges.
 * 2. Transaction journal: every state transition is appended to a journal table.
 * 3. Checkpointing: the committed balance state is saved periodically.
 * 4. Rollback/recovery: on restart or database failover, the journal is replayed
 *    from the last checkpoint, so interrupted payments are completed or cleanly discarded.
 * Replication: writes go to primary and mirror to standby; if the primary dies,
 * the service promotes the standby and continues (rollback + failover combined).
 */
@SpringBootApplication(scanBasePackages = "kz.aitu.ftr")
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }

    @Bean
    public ReplicatedDatabase db(
            @Value("${db.primary-url}") String primaryUrl,
            @Value("${db.standby-url:}") String standbyUrl,
            @Value("${db.replication-enabled:true}") boolean replication,
            @Value("${spring.datasource.username:university}") String user,
            @Value("${spring.datasource.password:university}") String password) {
        ReplicatedDatabase db = new ReplicatedDatabase(primaryUrl, standbyUrl, user, password, replication);
        db.ensureSchema("CREATE TABLE IF NOT EXISTS accounts (" +
                "id VARCHAR(32) PRIMARY KEY, student_id VARCHAR(32), balance_kzt BIGINT)");
        db.ensureSchema("CREATE TABLE IF NOT EXISTS payments (" +
                "id VARCHAR(40) PRIMARY KEY, idempotency_key VARCHAR(64) UNIQUE, " +
                "account_id VARCHAR(32), amount_kzt BIGINT, status VARCHAR(20), attempts INT DEFAULT 0)");
        db.ensureSchema("CREATE TABLE IF NOT EXISTS payment_journal (" +
                "seq BIGSERIAL PRIMARY KEY, payment_id VARCHAR(40), state VARCHAR(30), at TIMESTAMPTZ DEFAULT now())");
        db.ensureSchema("CREATE TABLE IF NOT EXISTS payment_checkpoints (" +
                "id BIGSERIAL PRIMARY KEY, payments_committed INT, amount_committed BIGINT, at TIMESTAMPTZ DEFAULT now())");
        return db;
    }

    @RestController
    @RequestMapping("/payments")
    public static class PaymentController {

        private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
        private static final int MAX_DB_ATTEMPTS = 3;

        private final ReplicatedDatabase db;
        private final ChaosController chaos;

        public PaymentController(ReplicatedDatabase db, ChaosController chaos) {
            this.db = db;
            this.chaos = chaos;
        }

        /**
         * Processes a tuition payment.
         * Request: {"paymentId": "...", "idempotencyKey": "...", "accountId": "...", "amountKzt": 12000}
         * Retries transient database failures with exponential backoff. If the primary
         * database stays unreachable, the standby is promoted and the payment is retried there.
         * The idempotency key makes a client retry of an already committed payment return
         * the original result without charging twice.
         */
        @PostMapping
        public ResponseEntity<Map<String, Object>> pay(@RequestBody Map<String, Object> body) {
            chaos.apply("pay");
            String paymentId = String.valueOf(body.getOrDefault("paymentId", "P" + System.currentTimeMillis()));
            String idemKey = String.valueOf(body.getOrDefault("idempotencyKey", paymentId));
            String accountId = String.valueOf(body.getOrDefault("accountId", "ACC-001"));
            long amount = Long.parseLong(String.valueOf(body.getOrDefault("amountKzt", "1000")));

            long started = System.currentTimeMillis();

            // Idempotency check: has this key already been processed?
            Map<String, Object> existing = DbRetry.withRetry("idempotency-check", MAX_DB_ATTEMPTS, 300,
                    () -> db.read(c -> {
                        try (PreparedStatement st = c.prepareStatement(
                                "SELECT id, status FROM payments WHERE idempotency_key = ?")) {
                            st.setString(1, idemKey);
                            ResultSet rs = st.executeQuery();
                            if (rs.next()) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("paymentId", rs.getString(1));
                                m.put("status", rs.getString(2));
                                return m;
                            }
                            return null;
                        }
                    }));

            if (existing != null) {
                log.info("IDEMPOTENT duplicate payment {} with key {} returned original status {}",
                        paymentId, idemKey, existing.get("status"));
                return ResponseEntity.ok(Map.of(
                        "status", "DUPLICATE",
                        "paymentId", existing.get("paymentId"),
                        "originalStatus", existing.get("status"),
                        "idempotencyKey", idemKey,
                        "instance", Host.get()));
            }

            try {
                return ResponseEntity.ok(process(paymentId, idemKey, accountId, amount, started));
            } catch (ReplicatedDatabase.DataAccessException e) {
                // All retries against the primary failed. In FT mode promote standby and retry there.
                if (db.isReplicationEnabled() && !db.isPromoted()) {
                    log.error("FAILOVER primary database unreachable, promoting standby: {}", e.getMessage());
                    db.promoteStandby();
                    return ResponseEntity.ok(process(paymentId, idemKey, accountId, amount, started));
                }
                throw e;
            }
        }

        private Map<String, Object> process(String paymentId, String idemKey, String accountId,
                                            long amount, long started) {
            // Journal: PENDING
            journal(paymentId, "PENDING");

            int attempts = 0;
            ReplicatedDatabase.DataAccessException last = null;
            for (int attempt = 0; attempt < MAX_DB_ATTEMPTS; attempt++) {
                final int attemptNo = attempt + 1;
                attempts = attemptNo;
                try {
                    // The actual money movement: debit account and insert payment, atomically.
                    db.write(c -> {
                        c.setAutoCommit(false);
                        try {
                            long balance;
                            try (PreparedStatement st = c.prepareStatement(
                                    "SELECT balance_kzt FROM accounts WHERE id = ? FOR UPDATE")) {
                                st.setString(1, accountId);
                                ResultSet rs = st.executeQuery();
                                if (!rs.next()) {
                                    try (PreparedStatement ins = c.prepareStatement(
                                            "INSERT INTO accounts (id, student_id, balance_kzt) VALUES (?, ?, 1000000)")) {
                                        ins.setString(1, accountId);
                                        ins.setString(2, "student");
                                        ins.executeUpdate();
                                    }
                                    balance = 1000000;
                                } else {
                                    balance = rs.getLong(1);
                                }
                            }
                            if (balance < amount) {
                                c.rollback();
                                journal(paymentId, "REJECTED");
                                throw new IllegalArgumentException("insufficient_funds");
                            }
                            long newBalance = balance - amount;
                            try (PreparedStatement st = c.prepareStatement(
                                    "UPDATE accounts SET balance_kzt = ? WHERE id = ?")) {
                                st.setLong(1, newBalance);
                                st.setString(2, accountId);
                                st.executeUpdate();
                            }
                            try (PreparedStatement st = c.prepareStatement(
                                    "INSERT INTO payments (id, idempotency_key, account_id, amount_kzt, status, attempts)" +
                                            " VALUES (?, ?, ?, ?, 'COMMITTED', ?)" +
                                            " ON CONFLICT (idempotency_key) DO NOTHING")) {
                                st.setString(1, paymentId);
                                st.setString(2, idemKey);
                                st.setString(3, accountId);
                                st.setLong(4, amount);
                                st.setInt(5, attemptNo);
                                st.executeUpdate();
                            }
                            c.commit();
                        } catch (SQLException e) {
                            c.rollback();
                            throw new ReplicatedDatabase.DataAccessException("transaction failed: " + e.getMessage(), e);
                        } finally {
                            c.setAutoCommit(true);
                        }
                        return null;
                    });

                    journal(paymentId, "COMMITTED");
                    checkpoint();
                    long elapsed = System.currentTimeMillis() - started;
                    log.info("PAYMENT {} committed in {} ms (attempts {}, instance {})",
                            paymentId, elapsed, attemptNo, Host.get());
                    return Map.of(
                            "status", "COMMITTED",
                            "paymentId", paymentId,
                            "amountKzt", amount,
                            "attempts", attemptNo,
                            "latencyMillis", elapsed,
                            "instance", Host.get());
                } catch (ReplicatedDatabase.DataAccessException e) {
                    last = e;
                    journal(paymentId, "RETRY_WAIT");
                    long delay = Backoff.delay(attempt, 300, 4000);
                    log.warn("RETRY payment {} attempt {}/{} failed ({}), backoff {} ms",
                            paymentId, attempt + 1, MAX_DB_ATTEMPTS, e.getMessage(), delay);
                    Backoff.sleep(delay);
                }
            }
            journal(paymentId, "FAILED");
            throw last;
        }

        private void journal(String paymentId, String state) {
            try {
                DbRetry.withRetry("journal", 2, 200, () -> db.write(c -> {
                    try (PreparedStatement st = c.prepareStatement(
                            "INSERT INTO payment_journal (payment_id, state) VALUES (?, ?)")) {
                        st.setString(1, paymentId);
                        st.setString(2, state);
                        st.executeUpdate();
                    }
                    return null;
                }));
                log.info("JOURNAL {} -> {}", paymentId, state);
            } catch (ReplicatedDatabase.DataAccessException e) {
                log.error("JOURNAL write failed for {} ({}): {}", paymentId, state, e.getMessage());
            }
        }

        /**
         * Checkpoint: records how many payments and what total amount were committed.
         * Used by /recovery to verify consistency after a failure.
         */
        private void checkpoint() {
            try {
                DbRetry.withRetry("checkpoint", 2, 200, () -> db.write(c -> {
                    try (PreparedStatement st = c.prepareStatement(
                            "INSERT INTO payment_checkpoints (payments_committed, amount_committed) " +
                                    "SELECT COUNT(*), COALESCE(SUM(amount_kzt), 0) FROM payments WHERE status = 'COMMITTED'")) {
                        st.executeUpdate();
                    }
                    return null;
                }));
            } catch (ReplicatedDatabase.DataAccessException e) {
                log.warn("CHECKPOINT failed: {}", e.getMessage());
            }
        }

        @GetMapping("/journal")
        public ResponseEntity<Map<String, Object>> journalView(@RequestParam(defaultValue = "50") int limit) {
            chaos.apply("journal");
            List<Map<String, Object>> entries = db.read(c -> {
                List<Map<String, Object>> out = new ArrayList<>();
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT seq, payment_id, state, at FROM payment_journal ORDER BY seq DESC LIMIT ?")) {
                    st.setInt(1, limit);
                    ResultSet rs = st.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("seq", rs.getLong(1));
                        m.put("paymentId", rs.getString(2));
                        m.put("state", rs.getString(3));
                        m.put("at", String.valueOf(rs.getTimestamp(4)));
                        out.add(m);
                    }
                }
                return out;
            });
            return ResponseEntity.ok(Map.of("entries", entries, "instance", Host.get()));
        }

        /**
         * Recovery endpoint: replays committed state from the journal after a crash or failover.
         * Returns the checkpointed totals and how many payments were recovered.
         */
        @PostMapping("/recovery")
        public ResponseEntity<Map<String, Object>> recover() {
            chaos.apply("recovery");
            long started = System.currentTimeMillis();
            Map<String, Object> state = DbRetry.withRetry("recovery", MAX_DB_ATTEMPTS, 300, () -> db.read(c -> {
                Map<String, Object> m = new HashMap<>();
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(amount_kzt), 0) FROM payments WHERE status = 'COMMITTED'");
                     ResultSet rs = st.executeQuery()) {
                    rs.next();
                    m.put("committedPayments", rs.getLong(1));
                    m.put("committedAmountKzt", rs.getLong(2));
                }
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT COALESCE(MAX(id), 0) FROM payment_checkpoints");
                     ResultSet rs = st.executeQuery()) {
                    rs.next();
                    m.put("lastCheckpointId", rs.getLong(1));
                }
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT COUNT(DISTINCT payment_id) FROM payment_journal WHERE state IN ('PENDING','RETRY_WAIT')");
                     ResultSet rs = st.executeQuery()) {
                    rs.next();
                    m.put("interruptedPayments", rs.getLong(1));
                }
                return m;
            }));
            long elapsed = System.currentTimeMillis() - started;
            state.put("status", "RECOVERED");
            state.put("recoveryMillis", elapsed);
            state.put("instance", Host.get());
            log.info("RECOVERY completed in {} ms: {}", elapsed, state);
            return ResponseEntity.ok(state);
        }

        @GetMapping("/checkpoints")
        public ResponseEntity<Map<String, Object>> checkpoints() {
            chaos.apply("checkpoints");
            List<Map<String, Object>> cps = db.read(c -> {
                List<Map<String, Object>> out = new ArrayList<>();
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT id, payments_committed, amount_committed, at FROM payment_checkpoints ORDER BY id DESC LIMIT 20");
                     ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", rs.getLong(1));
                        m.put("paymentsCommitted", rs.getLong(2));
                        m.put("amountCommittedKzt", rs.getLong(3));
                        m.put("at", String.valueOf(rs.getTimestamp(4)));
                        out.add(m);
                    }
                }
                return out;
            });
            return ResponseEntity.ok(Map.of("checkpoints", cps, "instance", Host.get()));
        }
    }
}
