package vip.mate.context.intelligence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Context Intelligence module configuration properties.
 * <p>
 * See design doc Appendix C.9 for full configuration (authoritative config).
 *
 * @author MateClaw Team
 */
@ConfigurationProperties(prefix = "mateclaw.context.intelligence")
public class ContextIntelligenceProperties {

    /** Module master switch; when false, the read path falls back to yml + hardcoded values */
    private boolean enabled = false;

    /** Backend diversity detection switch: auto (auto-detect) / off (disabled) */
    private String diversityDetection = "auto";

    /** Pressure inference switch */
    private boolean pressureInference = true;

    /** DB persistence switch */
    private boolean dbPersist = true;

    /** Dedicated thread pool config */
    private Executor executor = new Executor();

    /** Window probe state machine parameters */
    private Probe probe = new Probe();

    /** Diversity detection parameters */
    private Diversity diversity = new Diversity();

    /** Pressure inference parameters */
    private Pressure pressure = new Pressure();

    /** Budget allocation ratios */
    private Budget budget = new Budget();

    /** Idle model cleanup */
    private AutoClean autoClean = new AutoClean();

    /** Metrics monitoring */
    private Metrics metrics = new Metrics();

    // --- getters/setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDiversityDetection() { return diversityDetection; }
    public void setDiversityDetection(String diversityDetection) { this.diversityDetection = diversityDetection; }

    public boolean isPressureInference() { return pressureInference; }
    public void setPressureInference(boolean pressureInference) { this.pressureInference = pressureInference; }

    public boolean isDbPersist() { return dbPersist; }
    public void setDbPersist(boolean dbPersist) { this.dbPersist = dbPersist; }

    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor executor) { this.executor = executor; }

    public Probe getProbe() { return probe; }
    public void setProbe(Probe probe) { this.probe = probe; }

    public Diversity getDiversity() { return diversity; }
    public void setDiversity(Diversity diversity) { this.diversity = diversity; }

    public Pressure getPressure() { return pressure; }
    public void setPressure(Pressure pressure) { this.pressure = pressure; }

    public Budget getBudget() { return budget; }
    public void setBudget(Budget budget) { this.budget = budget; }

    public AutoClean getAutoClean() { return autoClean; }
    public void setAutoClean(AutoClean autoClean) { this.autoClean = autoClean; }

    public Metrics getMetrics() { return metrics; }
    public void setMetrics(Metrics metrics) { this.metrics = metrics; }

    // --- nested classes ---

    public static class Executor {
        private int coreSize = 8;
        private int maxQueueSize = 500;

        public int getCoreSize() { return coreSize; }
        public void setCoreSize(int coreSize) { this.coreSize = coreSize; }

        public int getMaxQueueSize() { return maxQueueSize; }
        public void setMaxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; }
    }

    public static class Probe {
        private int sampleSize = 20;
        private double overflowShrinkRatio = 0.85;
        private double binaryConvergence = 0.10;
        private long staleReprobeMs = 1_800_000;
        private int coldSeedFallback = 32_768;
        private int globalCeiling = 2_000_000;
        private long stablePersistIntervalMs = 180_000;

        public int getSampleSize() { return sampleSize; }
        public void setSampleSize(int sampleSize) { this.sampleSize = sampleSize; }

        public double getOverflowShrinkRatio() { return overflowShrinkRatio; }
        public void setOverflowShrinkRatio(double overflowShrinkRatio) { this.overflowShrinkRatio = overflowShrinkRatio; }

        public double getBinaryConvergence() { return binaryConvergence; }
        public void setBinaryConvergence(double binaryConvergence) { this.binaryConvergence = binaryConvergence; }

        public long getStaleReprobeMs() { return staleReprobeMs; }
        public void setStaleReprobeMs(long staleReprobeMs) { this.staleReprobeMs = staleReprobeMs; }

        public int getColdSeedFallback() { return coldSeedFallback; }
        public void setColdSeedFallback(int coldSeedFallback) { this.coldSeedFallback = coldSeedFallback; }

        public int getGlobalCeiling() { return globalCeiling; }
        public void setGlobalCeiling(int globalCeiling) { this.globalCeiling = globalCeiling; }

        public long getStablePersistIntervalMs() { return stablePersistIntervalMs; }
        public void setStablePersistIntervalMs(long stablePersistIntervalMs) { this.stablePersistIntervalMs = stablePersistIntervalMs; }
    }

    public static class Diversity {
        private int maxObservations = 500;
        private int safePercentile = 10;
        private double successVarianceThreshold = 5.0;
        private double overflowRatioThreshold = 3.0;
        private int decayHours = 24;

        public int getMaxObservations() { return maxObservations; }
        public void setMaxObservations(int maxObservations) { this.maxObservations = maxObservations; }

        public int getSafePercentile() { return safePercentile; }
        public void setSafePercentile(int safePercentile) { this.safePercentile = safePercentile; }

        public double getSuccessVarianceThreshold() { return successVarianceThreshold; }
        public void setSuccessVarianceThreshold(double successVarianceThreshold) { this.successVarianceThreshold = successVarianceThreshold; }

        public double getOverflowRatioThreshold() { return overflowRatioThreshold; }
        public void setOverflowRatioThreshold(double overflowRatioThreshold) { this.overflowRatioThreshold = overflowRatioThreshold; }

        public int getDecayHours() { return decayHours; }
        public void setDecayHours(int decayHours) { this.decayHours = decayHours; }
    }

    public static class Pressure {
        private int successDegradeStep = 5;
        private int overflowEscalateThreshold = 3;
        private long latencyEscalateMs = 30_000;
        private int minLatencySamples = 3;

        public int getSuccessDegradeStep() { return successDegradeStep; }
        public void setSuccessDegradeStep(int successDegradeStep) { this.successDegradeStep = successDegradeStep; }

        public int getOverflowEscalateThreshold() { return overflowEscalateThreshold; }
        public void setOverflowEscalateThreshold(int overflowEscalateThreshold) { this.overflowEscalateThreshold = overflowEscalateThreshold; }

        public long getLatencyEscalateMs() { return latencyEscalateMs; }
        public void setLatencyEscalateMs(long latencyEscalateMs) { this.latencyEscalateMs = latencyEscalateMs; }

        public int getMinLatencySamples() { return minLatencySamples; }
        public void setMinLatencySamples(int minLatencySamples) { this.minLatencySamples = minLatencySamples; }
    }

    public static class Budget {
        private double normalHistoryRatio = 0.60;
        private double elevatedHistoryRatio = 0.70;
        private double highHistoryRatio = 0.80;
        private double criticalHistoryRatio = 0.85;
        private double outputReserveRatio = 0.15;
        private double keepTailRatio = 0.60;

        public double getNormalHistoryRatio() { return normalHistoryRatio; }
        public void setNormalHistoryRatio(double v) { this.normalHistoryRatio = v; }

        public double getElevatedHistoryRatio() { return elevatedHistoryRatio; }
        public void setElevatedHistoryRatio(double v) { this.elevatedHistoryRatio = v; }

        public double getHighHistoryRatio() { return highHistoryRatio; }
        public void setHighHistoryRatio(double v) { this.highHistoryRatio = v; }

        public double getCriticalHistoryRatio() { return criticalHistoryRatio; }
        public void setCriticalHistoryRatio(double v) { this.criticalHistoryRatio = v; }

        public double getOutputReserveRatio() { return outputReserveRatio; }
        public void setOutputReserveRatio(double v) { this.outputReserveRatio = v; }

        public double getKeepTailRatio() { return keepTailRatio; }
        public void setKeepTailRatio(double v) { this.keepTailRatio = v; }
    }

    public static class AutoClean {
        private boolean enabled = true;
        private long idleThresholdMs = 3_600_000;
        private long cleanIntervalMs = 3_600_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public long getIdleThresholdMs() { return idleThresholdMs; }
        public void setIdleThresholdMs(long idleThresholdMs) { this.idleThresholdMs = idleThresholdMs; }

        public long getCleanIntervalMs() { return cleanIntervalMs; }
        public void setCleanIntervalMs(long cleanIntervalMs) { this.cleanIntervalMs = cleanIntervalMs; }
    }

    public static class Metrics {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
