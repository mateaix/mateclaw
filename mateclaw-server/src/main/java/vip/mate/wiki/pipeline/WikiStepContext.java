package vip.mate.wiki.pipeline;

import java.util.Map;

/**
 * Inputs available to a {@link WikiStepExecutor}: the KB, the owner agent the
 * step runs under (for permission checks), the step's declared config, and the
 * output of the previous step.
 *
 * @author MateClaw Team
 */
public record WikiStepContext(
        Long kbId,
        Long ownerAgentId,
        String stepId,
        Map<String, Object> stepConfig,
        String previousOutput) {
}
