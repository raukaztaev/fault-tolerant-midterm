package kz.aitu.ftr.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fault injection controller exposed by every service under /chaos.
 * Modes: ok (normal), delay (adds latency), error (returns 500), crash (kills the process).
 * delayProbability and errorProbability make injections probabilistic.
 * Disabled when resilience.mode=baseline so baseline and fault-tolerant runs share the same image.
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    public enum Mode { OK, DELAY, ERROR, CRASH }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);
    private final AtomicInteger delayMillis = new AtomicInteger(0);
    private final AtomicInteger delayProbability = new AtomicInteger(100);
    private final AtomicInteger errorProbability = new AtomicInteger(100);
    private final AtomicReference<String> affectedEndpoint = new AtomicReference<>("*");
    private final ConcurrentHashMap<String, AtomicInteger> injectedCounts = new ConcurrentHashMap<>();
    private volatile long enabledAt;

    private final ResilienceMode resilienceMode;

    public ChaosController(ResilienceMode resilienceMode) {
        this.resilienceMode = resilienceMode;
    }

    @PostMapping("/{mode}")
    public ResponseEntity<Map<String, String>> setMode(
            @PathVariable String mode,
            @RequestParam(defaultValue = "500") int delayMillis,
            @RequestParam(defaultValue = "100") int delayProbability,
            @RequestParam(defaultValue = "100") int errorProbability,
            @RequestParam(defaultValue = "*") String endpoint) {

        if (resilienceMode.isBaseline()) {
            return ResponseEntity.ok(Map.of(
                    "status", "ignored",
                    "reason", "baseline mode: fault tolerance disabled, chaos injection is a no-op"));
        }

        Mode newMode;
        try {
            newMode = Mode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown mode: " + mode));
        }

        if (newMode == Mode.DELAY || newMode == Mode.ERROR) {
            if (delayProbability < 0 || delayProbability > 100 || errorProbability < 0 || errorProbability > 100) {
                return ResponseEntity.badRequest().body(Map.of("error", "probability must be 0..100"));
            }
        }

        this.mode.set(newMode);
        this.delayMillis.set(delayMillis);
        this.delayProbability.set(delayProbability);
        this.errorProbability.set(errorProbability);
        this.affectedEndpoint.set(endpoint);
        enabledAt = System.currentTimeMillis();

        log.warn("CHAOS mode={} delay={}ms delayProb={}% errorProb={}% endpoint={}",
                newMode, delayMillis, delayProbability, errorProbability, endpoint);
        return ResponseEntity.ok(Map.of(
                "status", "set",
                "mode", newMode.name(),
                "delayMillis", String.valueOf(delayMillis),
                "delayProbability", delayProbability + "%",
                "errorProbability", errorProbability + "%",
                "endpoint", endpoint));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        mode.set(Mode.OK);
        delayMillis.set(0);
        delayProbability.set(100);
        errorProbability.set(100);
        affectedEndpoint.set("*");
        injectedCounts.clear();
        log.info("CHAOS reset to OK");
        return ResponseEntity.ok(Map.of("status", "reset"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "mode", mode.get().name(),
                "delayMillis", delayMillis.get(),
                "delayProbability", delayProbability.get(),
                "errorProbability", errorProbability.get(),
                "endpoint", affectedEndpoint.get(),
                "injected", Map.copyOf(injectedCounts),
                "enabledForMillis", System.currentTimeMillis() - enabledAt));
    }

    /**
     * Called by service code at the start of a request handling path.
     * Applies delay or throws ChaosException according to the current mode.
     */
    public void apply(String endpoint) {
        Mode m = mode.get();
        if (m == Mode.OK) {
            return;
        }
        String target = affectedEndpoint.get();
        if (!"*".equals(target) && !target.equals(endpoint)) {
            return;
        }
        if (m == Mode.DELAY) {
            if (ThreadLocalRandom.current().nextInt(100) < delayProbability.get()) {
                inject("delay:" + endpoint);
                sleep(delayMillis.get());
            }
        } else if (m == Mode.ERROR) {
            if (ThreadLocalRandom.current().nextInt(100) < errorProbability.get()) {
                inject("error:" + endpoint);
                throw new ChaosException("injected error at " + endpoint);
            }
        }
    }

    private void inject(String key) {
        injectedCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class ChaosException extends RuntimeException {
        public ChaosException(String message) {
            super(message);
        }
    }
}
