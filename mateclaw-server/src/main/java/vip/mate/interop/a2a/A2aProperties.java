package vip.mate.interop.a2a;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mateclaw.a2a")
public class A2aProperties {

    private boolean enabled = false;

    private String baseUrl;

    private long callTimeoutMs = 120_000L;

    private int maxTasks = 1_000;

    private long taskTtlSeconds = 3_600L;

    private int maxResponseBytes = 1_048_576;

    private long outboundTimeoutMs = 120_000L;

    private boolean allowPrivateOutbound = false;

    private long sweepIntervalMs = 60_000L;
}
