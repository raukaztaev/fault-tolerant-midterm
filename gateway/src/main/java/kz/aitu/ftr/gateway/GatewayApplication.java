package kz.aitu.ftr.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kz.aitu.ftr.common.Backoff;
import kz.aitu.ftr.common.ChaosController;
import kz.aitu.ftr.common.ResilienceMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * API Gateway.
 * Software fault-tolerance mechanisms implemented here:
 * 1. Client-side load balancing across service replicas (round robin with failover).
 * 2. Request timeouts (configurable, protects against slow responses).
 * 3. Retry with exponential backoff (retries GETs and idempotent POSTs).
 * 4. Circuit breaker per downstream service: after repeated failures requests
 *    fail fast and the gateway reports the service as temporarily unavailable,
 *    letting the half-open probe test recovery.
 */
@SpringBootApplication(scanBasePackages = "kz.aitu.ftr")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @RestController
    public static class GatewayController {

        private static final Logger log = LoggerFactory.getLogger(GatewayController.class);
        private static final int MAX_RETRIES = 3;

        private final ResilienceMode resilienceMode;
        private final RestClient http;
        private final long timeoutMillis;
        private final Map<String, List<String>> routes;
        private final Map<String, AtomicInteger> roundRobin = new HashMap<>();
        private final Map<String, CircuitBreaker> breakers = new HashMap<>();
        private final Map<String, ServiceStats> stats = new HashMap<>();

        GatewayController(ResilienceMode resilienceMode,
                          @Value("${gateway.routes.student:http://student-1:8081}") String student,
                          @Value("${gateway.routes.payment:http://payment-1:8082}") String payment,
                          @Value("${gateway.routes.records:http://records-1:8083}") String records,
                          @Value("${gateway.routes.timetable:http://timetable-1:8084}") String timetable,
                          @Value("${gateway.timeout-millis:3000}") long timeoutMillis) {
            this.resilienceMode = resilienceMode;
            this.timeoutMillis = timeoutMillis;
            this.routes = new LinkedHashMap<>();
            routes.put("students", List.of(student.split(",")));
            routes.put("payments", List.of(payment.split(",")));
            routes.put("records", List.of(records.split(",")));
            routes.put("timetable", List.of(timetable.split(",")));
            for (Map.Entry<String, List<String>> e : routes.entrySet()) {
                roundRobin.put(e.getKey(), new AtomicInteger());
                stats.put(e.getKey(), new ServiceStats());
                CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .slidingWindowSize(8)
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .build();
                breakers.put(e.getKey(), CircuitBreakerRegistry.ofDefaults().circuitBreaker(e.getKey(), cfg));
            }
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout(Duration.ofMillis(timeoutMillis));
            f.setReadTimeout(Duration.ofMillis(timeoutMillis));
            this.http = RestClient.builder().requestFactory(f).build();
        }

        /**
         * Generic forwarding with the full fault-tolerance chain:
         * circuit breaker check, per-replica failover, retry with backoff, timeouts.
         * In baseline mode: single instance, no retry, no failover, no breaker.
         */
        private ResponseEntity<String> forward(String service, String path, String method, String body) {
            long started = System.currentTimeMillis();
            String requestId = UUID.randomUUID().toString().substring(0, 8);
            ServiceStats s = stats.get(service);
            s.requests.incrementAndGet();

            List<String> instances = routes.get(service);
            int startIdx = resilienceMode.isBaseline() ? 0
                    : Math.floorMod(roundRobin.get(service).getAndIncrement(), instances.size());

            Exception lastError = null;
            int attempts = 0;
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                int idx = Math.floorMod(startIdx + attempt, instances.size());
                String url = instances.get(idx) + path;
                attempts++;
                try {
                    ResponseEntity<String> resp = doCall(url, method, body);
                    long elapsed = System.currentTimeMillis() - started;
                    s.successes.incrementAndGet();
                    s.totalLatency.addAndGet(elapsed);
                    log.info("GATEWAY [{}] {} {} -> {} via instance {} ({} ms, attempt {}, cb={})",
                            requestId, method, path, resp.getStatusCode().value(), idx, elapsed, attempts,
                            breakerState(service));
                    return ResponseEntity.status(resp.getStatusCode())
                            .header("X-Request-Id", requestId)
                            .header("X-Gateway-Attempts", String.valueOf(attempts))
                            .header("X-Gateway-Instance", String.valueOf(idx))
                            .body(resp.getBody());
                } catch (Exception e) {
                    lastError = e;
                    s.failures.incrementAndGet();
                    log.warn("GATEWAY [{}] {} {} attempt {}/{} failed on instance {}: {}",
                            requestId, method, path, attempts, MAX_RETRIES, idx, rootMessage(e));
                    if (attempt < MAX_RETRIES - 1) {
                        long delay = Backoff.delay(attempt, 200, 2000);
                        log.info("GATEWAY [{}] retrying in {} ms", requestId, delay);
                        Backoff.sleep(delay);
                    }
                }
            }
            long elapsed = System.currentTimeMillis() - started;
            log.error("GATEWAY [{}] {} {} FAILED after {} attempts and {} ms: {}",
                    requestId, method, path, attempts, elapsed, rootMessage(lastError));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("X-Request-Id", requestId)
                    .header("X-Gateway-Attempts", String.valueOf(attempts))
                    .body(Map.of(
                            "error", "service_unavailable",
                            "service", service,
                            "attempts", attempts,
                            "elapsedMillis", elapsed,
                            "detail", rootMessage(lastError)).toString());
        }

        private ResponseEntity<String> doCall(String url, String method, String body) {
            if (method.equalsIgnoreCase("POST")) {
                return http.post().uri(url)
                        .header("Content-Type", "application/json")
                        .body(body == null ? "{}" : body)
                        .retrieve().toEntity(String.class);
            }
            return http.get().uri(url).retrieve().toEntity(String.class);
        }

        private String breakerState(String service) {
            return resilienceMode.isBaseline() ? "disabled" : breakers.get(service).getState().name();
        }

        private static String rootMessage(Throwable t) {
            while (t.getCause() != null && t.getCause() != t) {
                t = t.getCause();
            }
            return String.valueOf(t.getMessage());
        }

        @GetMapping("/api/students/**")
        public ResponseEntity<String> studentsGet(HttpServletRequest req) {
            return withBreaker("students", () -> forward("students", pathAfter(req, "/api/students", "/students"), "GET", null));
        }

        @PostMapping("/api/students/**")
        public ResponseEntity<String> studentsPost(HttpServletRequest req, @RequestBody String body) {
            return withBreaker("students", () -> forward("students", pathAfter(req, "/api/students", "/students"), "POST", body));
        }

        @GetMapping("/api/payments/journal")
        public ResponseEntity<String> paymentJournal() {
            return withBreaker("payments", () -> forward("payments", "/payments/journal", "GET", null));
        }

        @GetMapping("/api/payments/checkpoints")
        public ResponseEntity<String> paymentCheckpoints() {
            return withBreaker("payments", () -> forward("payments", "/payments/checkpoints", "GET", null));
        }

        @PostMapping("/api/payments")
        public ResponseEntity<String> pay(@RequestBody String body) {
            return withBreaker("payments", () -> forward("payments", "/payments", "POST", body));
        }

        @PostMapping("/api/payments/recovery")
        public ResponseEntity<String> paymentRecovery() {
            return withBreaker("payments", () -> forward("payments", "/payments/recovery", "POST", "{}"));
        }

        @GetMapping("/api/records/**")
        public ResponseEntity<String> recordsGet(HttpServletRequest req) {
            return withBreaker("records", () -> forward("records", pathAfter(req, "/api/records", "/records"), "GET", null));
        }

        @PostMapping("/api/records/**")
        public ResponseEntity<String> recordsPost(HttpServletRequest req, @RequestBody String body) {
            return withBreaker("records", () -> forward("records", pathAfter(req, "/api/records", "/records"), "POST", body));
        }

        @GetMapping("/api/timetable/**")
        public ResponseEntity<String> timetableGet(HttpServletRequest req) {
            return withBreaker("timetable", () -> forward("timetable", pathAfter(req, "/api/timetable", "/timetable"), "GET", null));
        }

        @PostMapping("/api/timetable/**")
        public ResponseEntity<String> timetablePost(HttpServletRequest req, @RequestBody String body) {
            return withBreaker("timetable", () -> forward("timetable", pathAfter(req, "/api/timetable", "/timetable"), "POST", body));
        }

        private ResponseEntity<String> withBreaker(String service, Supplier<ResponseEntity<String>> call) {
            if (resilienceMode.isBaseline()) {
                return call.get();
            }
            CircuitBreaker cb = breakers.get(service);
            try {
                return cb.executeSupplier(call);
            } catch (Exception e) {
                log.warn("GATEWAY circuit breaker {} OPEN, failing fast: {}", service, rootMessage(e));
                stats.get(service).breakerRejections.incrementAndGet();
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(
                                "error", "circuit_open",
                                "service", service,
                                "message", "service temporarily unavailable, please retry later").toString());
            }
        }

        private static String pathAfter(HttpServletRequest req, String prefix, String servicePath) {
            String uri = req.getRequestURI();
            String rest = uri.length() > prefix.length() ? uri.substring(prefix.length()) : "";
            return servicePath + rest;
        }

        /**
         * Gateway observability endpoint: per-service request/failure statistics
         * used by the experiment evaluation to compute availability.
         */
        @GetMapping("/api/stats")
        public ResponseEntity<Map<String, Object>> stats() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, ServiceStats> e : stats.entrySet()) {
                ServiceStats s = e.getValue();
                long total = s.requests.get();
                long succ = s.successes.get();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("requests", total);
                m.put("successes", succ);
                m.put("failures", s.failures.get());
                m.put("availabilityPercent", total == 0 ? 100.0 : Math.round(succ * 10000.0 / total) / 100.0);
                m.put("avgLatencyMillis", succ == 0 ? 0 : s.totalLatency.get() / succ);
                m.put("breakerRejections", s.breakerRejections.get());
                m.put("circuitState", breakerState(e.getKey()));
                out.put(e.getKey(), m);
            }
            out.put("uptimeSince", Instant.now().toString());
            return ResponseEntity.ok(out);
        }

        @PostMapping("/api/reset-stats")
        public ResponseEntity<Map<String, Object>> resetStats() {
            stats.values().forEach(s -> {
                s.requests.set(0);
                s.successes.set(0);
                s.failures.set(0);
                s.totalLatency.set(0);
                s.breakerRejections.set(0);
            });
            return ResponseEntity.ok(Map.of("status", "reset"));
        }

        private static class ServiceStats {
            final AtomicLong requests = new AtomicLong();
            final AtomicLong successes = new AtomicLong();
            final AtomicLong failures = new AtomicLong();
            final AtomicLong totalLatency = new AtomicLong();
            final AtomicLong breakerRejections = new AtomicLong();
        }
    }
}
