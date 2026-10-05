package kz.aitu.ftr.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Manual retry with exponential backoff for database writes.
 * Used by services that must survive transient database failures without Resilience4j annotations.
 */
public final class DbRetry {

    private static final Logger log = LoggerFactory.getLogger(DbRetry.class);

    public interface Work<T> {
        T run() throws ReplicatedDatabase.DataAccessException;
    }

    public static <T> T withRetry(String operation, int maxAttempts, long baseMillis,
                                  Work<T> work) {
        ReplicatedDatabase.DataAccessException last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return work.run();
            } catch (ReplicatedDatabase.DataAccessException e) {
                last = e;
                long delay = Backoff.delay(attempt, baseMillis, 4000);
                log.warn("RETRY {} attempt {}/{} failed ({}), next in {} ms",
                        operation, attempt + 1, maxAttempts, e.getMessage(), delay);
                Backoff.sleep(delay);
            }
        }
        throw last;
    }
}
