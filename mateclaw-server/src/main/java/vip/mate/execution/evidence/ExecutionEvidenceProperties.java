package vip.mate.execution.evidence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "mateclaw.execution-evidence")
public class ExecutionEvidenceProperties {
    public enum Mode { OFF, OBSERVE, ENFORCE }
    private Mode mode = Mode.OBSERVE;
    private int retentionDays = 90;
    private int artifactVersionCheckMaxBytes = 1_048_576;
    public int getArtifactVersionCheckMaxBytes() { return artifactVersionCheckMaxBytes; }
    public void setArtifactVersionCheckMaxBytes(int value) { artifactVersionCheckMaxBytes = Math.clamp(value, 0, 16_777_216); }
    private int cleanupMaxBatches = 10;
    public int getCleanupMaxBatches() { return cleanupMaxBatches; }
    public void setCleanupMaxBatches(int value) { cleanupMaxBatches = Math.clamp(value, 1, 100); }
    private int maxObservations = 32;
    public int getMaxObservations() { return maxObservations; }
    public void setMaxObservations(int value) { maxObservations = Math.clamp(value, 1, 99); }
    private int maxSummaryBytes = 2048;
    private int defaultListLimit = 20;
    private int maxListLimit = 100;
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int value) { retentionDays = Math.max(1, value); }
    public int getMaxSummaryBytes() { return maxSummaryBytes; }
    public void setMaxSummaryBytes(int value) { maxSummaryBytes = Math.clamp(value, 1, 2048); }
    public int getDefaultListLimit() { return defaultListLimit; }
    public void setDefaultListLimit(int value) { defaultListLimit = Math.clamp(value, 1, 100); }
    public int getMaxListLimit() { return maxListLimit; }
    public void setMaxListLimit(int value) { maxListLimit = Math.clamp(value, 1, 100); }
}
