package kz.aitu.ftr.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Wraps a downstream call so that when the circuit is open the service answers
 * with a degraded response instead of hanging or failing.
 */
public final class Graceful {

    private static final Logger log = LoggerFactory.getLogger(Graceful.class);

    public static <T> ResponseEntity<Map<String, Object>> degraded(String message, T fallbackData) {
        log.warn("DEGRADED {}", message);
        return ResponseEntity.status(200).body(Map.of(
                "status", "DEGRADED",
                "message", message,
                "data", fallbackData == null ? Map.of() : fallbackData));
    }
}
