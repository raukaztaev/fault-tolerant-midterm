package kz.aitu.ftr.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Uniform error responses and structured error logging for all services.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ChaosController.ChaosException.class)
    public ResponseEntity<Map<String, Object>> chaos(ChaosController.ChaosException e) {
        log.error("ERROR injected fault: {}", e.getMessage());
        return ResponseEntity.status(503).body(Map.of(
                "error", "injected_fault",
                "message", e.getMessage()));
    }

    @ExceptionHandler(ReplicatedDatabase.DataAccessException.class)
    public ResponseEntity<Map<String, Object>> db(ReplicatedDatabase.DataAccessException e) {
        log.error("ERROR database access failed: {}", e.getMessage());
        return ResponseEntity.status(503).body(Map.of(
                "error", "database_unavailable",
                "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        log.error("ERROR unhandled: {}", e.toString());
        return ResponseEntity.status(500).body(Map.of(
                "error", "internal_error",
                "message", String.valueOf(e.getMessage())));
    }
}
