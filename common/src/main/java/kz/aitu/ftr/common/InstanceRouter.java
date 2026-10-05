package kz.aitu.ftr.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin client for calling one of several replicas of a downstream service.
 * Dead instances are skipped (client-side load balancing with failover).
 * In baseline mode the first instance is always used, so a dead replica breaks requests.
 */
@Component
public class InstanceRouter {

    private static final Logger log = LoggerFactory.getLogger(InstanceRouter.class);

    private final String[] instances;
    private final AtomicInteger counter = new AtomicInteger();
    private final long timeoutMillis;
    private final boolean baseline;
    private final String serviceName;

    public InstanceRouter(@Value("${router.service-name:service}") String serviceName,
                          @Value("${router.instances:}") List<String> instances,
                          @Value("${router.timeout-millis:2000}") long timeoutMillis,
                          ResilienceMode resilienceMode) {
        this.serviceName = serviceName;
        this.instances = instances.toArray(new String[0]);
        this.timeoutMillis = timeoutMillis;
        this.baseline = resilienceMode.isBaseline();
    }

    public int instanceCount() {
        return instances.length;
    }

    public String instanceUrl(int index) {
        return instances[index];
    }

    /**
     * Calls the given path on each replica in turn until one answers within the timeout.
     * Returns the parsed response body, or throws the last exception if all replicas fail.
     */
    public <T> T callAny(String path, Class<T> responseType) {
        Exception last = null;
        int start = baseline ? 0 : Math.floorMod(counter.getAndIncrement(), instances.length);
        for (int i = 0; i < instances.length; i++) {
            int idx = Math.floorMod(start + i, instances.length);
            String url = instances[idx] + path;
            try {
                T body = client().get().uri(url).retrieve().body(responseType);
                if (!baseline && i > 0) {
                    log.info("FAILOVER {} succeeded on instance {} after {} failed attempt(s)",
                            serviceName, idx, i);
                }
                return body;
            } catch (Exception e) {
                log.warn("FAILOVER {} instance {} failed: {}", serviceName, idx, e.getMessage());
                last = e;
            }
        }
        throw new IllegalStateException("All instances of " + serviceName + " failed", last);
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        f.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return RestClient.builder().requestFactory(f).build();
    }
}
