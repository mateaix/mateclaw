package vip.mate.context.adaptive;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mateclaw.context.adaptive")
public class AdaptiveContextProperties {
    private boolean enabled = true;
    private String gatewayMode = "auto";
    private int maxWindow = 2_000_000;
    private boolean dbPersist = true;
    private int probeConsecutiveSuccesses = 3;
    private long staleReenterProbingMs = 30 * 60_000;
    private double overflowShrinkRatio = 0.85;
    private double binaryConvergence = 0.10;
    private int gatewayPercentile = 10;
    private int gatewayConsecutiveOverflows = 2;
}
