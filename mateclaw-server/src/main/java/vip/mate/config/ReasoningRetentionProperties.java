package vip.mate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much of a turn's reasoning is written to the message record.
 * <p>
 * <b>Tune in {@code application.yml} under {@code mate.agent.reasoning}, not in
 * the Java field defaults.</b> The yml is the source of truth; the field default
 * below is a conservative fallback for tests / unit constructors.
 * <p>
 * A ReAct turn reasons once per iteration. Only the terminal iteration's
 * reasoning is needed to explain the answer, but the earlier ones are what
 * explain each tool call — which is exactly what a replay of a misbehaving turn
 * needs. Persisting all of them costs message-row size, so operators running
 * long tool loops on a small database can trade the detail away.
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.agent.reasoning")
public class ReasoningRetentionProperties {

    /** {@link Retention#ALL} keeps every iteration; {@link Retention#TERMINAL} keeps only the last. */
    private Retention retention = Retention.ALL;

    public boolean persistsEveryIteration() {
        return retention != Retention.TERMINAL;
    }

    public enum Retention {
        /** Persist the reasoning of every iteration, positioned where it happened. */
        ALL,
        /** Persist only the reasoning of the iteration that produced the final answer. */
        TERMINAL
    }
}
