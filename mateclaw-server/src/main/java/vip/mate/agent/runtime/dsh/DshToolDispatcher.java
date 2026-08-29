package vip.mate.agent.runtime.dsh;

import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DshToolDispatcher {
    private final Map<String, ToolCallback> callbacks;
    private final DshToolPolicy policy;
    private final DshToolPolicyEvaluator policyEvaluator;

    public DshToolDispatcher(List<ToolCallback> callbacks, DshToolPolicy policy,
                             DshToolPolicyEvaluator policyEvaluator) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        if (callbacks != null) {
            for (ToolCallback callback : callbacks) {
                if (callback != null && callback.getToolDefinition() != null) {
                    byName.putIfAbsent(callback.getToolDefinition().name(), callback);
                }
            }
        }
        this.callbacks = Map.copyOf(byName);
        this.policy = policy;
        this.policyEvaluator = policyEvaluator;
    }

    public DshToolDispatchResult dispatch(String toolName, String argumentsJson, Path targetPath) {
        ToolCallback callback = callbacks.get(toolName);
        if (callback == null) return DshToolDispatchResult.denied("unknown tool");
        DshToolDecision decision = policyEvaluator.decide(policy, toolName, targetPath);
        if (decision == DshToolDecision.DENY) return DshToolDispatchResult.denied("tool denied by policy");
        if (decision == DshToolDecision.APPROVAL) return DshToolDispatchResult.approval("tool approval required");
        try {
            return DshToolDispatchResult.allowed(callback.call(argumentsJson == null ? "{}" : argumentsJson));
        } catch (RuntimeException e) {
            return DshToolDispatchResult.denied("tool execution failed");
        }
    }
}
