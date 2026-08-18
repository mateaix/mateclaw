package vip.mate.agent.graph.guard;

import vip.mate.agent.graph.state.ActionExecutionLedger;

/** Pure completion decision for action-required ReAct turns. */
public final class ActionCompletionPolicy {

    public static final int MAX_RETRIES = 1;

    public enum Decision { ALLOW, RETRY, UNVERIFIED, FAILED }

    private ActionCompletionPolicy() {
    }

    public static Decision evaluate(boolean actionRequired, int retryCount,
                                    ActionExecutionLedger ledger) {
        if (!actionRequired) return Decision.ALLOW;
        ActionExecutionLedger evidence = ledger != null ? ledger : ActionExecutionLedger.empty();
        if (evidence.hasSuccessfulSubstantiveCall()) return Decision.ALLOW;
        if (evidence.hasSubstantiveAttempt()) return Decision.FAILED;
        return retryCount < MAX_RETRIES ? Decision.RETRY : Decision.UNVERIFIED;
    }
}
