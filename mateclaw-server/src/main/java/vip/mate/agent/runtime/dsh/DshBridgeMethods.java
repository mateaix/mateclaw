package vip.mate.agent.runtime.dsh;

import java.util.Set;

public final class DshBridgeMethods {
    private static final Set<String> SUPPORTED = Set.of(
            "session/open", "session/prompt", "session/cancel", "policy/update", "context/usage",
            "ready", "tool/call", "approval/ask", "subagent/lifecycle", "tool/cancel");

    private DshBridgeMethods() {}

    public static boolean isSupported(String method) {
        return method != null && SUPPORTED.contains(method);
    }
}
