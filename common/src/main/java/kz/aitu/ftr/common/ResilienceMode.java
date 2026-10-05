package kz.aitu.ftr.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the resilience.mode setting (ft or baseline).
 * In baseline mode all fault-tolerance behaviour (retry, failover, graceful degradation)
 * is switched off so experiments can measure the unprotected system.
 */
@Component
public class ResilienceMode {

    private final boolean baseline;

    public ResilienceMode(@Value("${resilience.mode:ft}") String mode) {
        this.baseline = "baseline".equalsIgnoreCase(mode);
    }

    public boolean isBaseline() {
        return baseline;
    }

    public boolean isFt() {
        return !baseline;
    }
}
