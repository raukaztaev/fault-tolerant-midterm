package kz.aitu.ftr.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple exponential backoff helper used by services that retry downstream calls manually.
 * baseMillis * 2^attempt, capped at maxMillis.
 */
public final class Backoff {

    private static final Logger log = LoggerFactory.getLogger(Backoff.class);

    private Backoff() {
    }

    public static long delay(int attempt, long baseMillis, long maxMillis) {
        long d = baseMillis;
        for (int i = 0; i < attempt && d < maxMillis; i++) {
            d = Math.min(d * 2, maxMillis);
        }
        return d;
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("BACKOFF sleep interrupted");
        }
    }
}
