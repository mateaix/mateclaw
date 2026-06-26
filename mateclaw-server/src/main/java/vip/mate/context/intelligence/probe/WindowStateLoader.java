package vip.mate.context.intelligence.probe;

/**
 * Window state loader (dependency inversion, to prevent the probe package from reverse depending on the persist package).
 * <p>
 * Implemented by {@code vip.mate.context.intelligence.persist.WindowStateRepository},
 * injected into {@link WindowProbeRegistry} in {@code ContextIntelligenceAutoConfiguration}.
 * <p>
 * When DB persistence is disabled or the Repository is not ready, the Registry degrades to null (always cold start).
 *
 * @author MateClaw Team
 */
@FunctionalInterface
public interface WindowStateLoader {

    /**
     * Load the persisted state snapshot by (provider, modelName).
     *
     * @param provider  provider ID
     * @param modelName model name
     * @return the snapshot; returns null when there is no record
     */
    WindowProbeSnapshot load(String provider, String modelName);
}
