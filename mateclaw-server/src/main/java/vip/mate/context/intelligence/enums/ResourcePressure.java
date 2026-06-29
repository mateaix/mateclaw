package vip.mate.context.intelligence.enums;

/**
 * Resource pressure level enum.
 * <p>
 * Used by {@code PressureInferencer} to infer the current resource pressure of the LLM backend,
 * which affects the history/injection allocation ratio in {@code TokenBudgetPlanner}.
 * <p>
 * The numeric mapping is used for Micrometer Gauge reporting (0/1/2/3).
 *
 * @author MateClaw Team
 */
public enum ResourcePressure {

    /** Normal pressure, history ratio 60% */
    NORMAL(0),

    /** Mild pressure, history ratio 70% */
    ELEVATED(1),

    /** High pressure, history ratio 80% */
    HIGH(2),

    /** Severe pressure, history ratio 85% */
    CRITICAL(3);

    private final int gaugeValue;

    ResourcePressure(int gaugeValue) {
        this.gaugeValue = gaugeValue;
    }

    /** Micrometer Gauge reporting value */
    public int gaugeValue() {
        return gaugeValue;
    }
}
