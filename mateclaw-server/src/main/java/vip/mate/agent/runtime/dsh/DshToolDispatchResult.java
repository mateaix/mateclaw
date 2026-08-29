package vip.mate.agent.runtime.dsh;

public record DshToolDispatchResult(DshToolDecision decision, String output, String error) {
    public static DshToolDispatchResult allowed(String output) {
        return new DshToolDispatchResult(DshToolDecision.ALLOW, output, null);
    }

    public static DshToolDispatchResult denied(String error) {
        return new DshToolDispatchResult(DshToolDecision.DENY, null, error);
    }

    public static DshToolDispatchResult approval(String reason) {
        return new DshToolDispatchResult(DshToolDecision.APPROVAL, null, reason);
    }
}
