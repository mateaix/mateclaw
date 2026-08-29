package vip.mate.agent.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationTurnGateTest {
    @Test void onlyOneTurnOwnsConversationAndOldReleaseCannotReleaseNewOwner() {
        ConversationTurnGate gate = new ConversationTurnGate();
        var first = gate.tryAcquire("conversation");
        assertNotNull(first);
        assertNull(gate.tryAcquire("conversation"));
        assertNotNull(gate.tryAcquire("other"));
        first.close();
        var second = gate.tryAcquire("conversation");
        assertNotNull(second);
        first.close();
        assertNull(gate.tryAcquire("conversation"));
        second.close();
        assertNotNull(gate.tryAcquire("conversation"));
    }

    @Test void admittedBackgroundCallCanEnterLifecycleWithoutReleasingOuterPermit() {
        ConversationTurnGate gate = new ConversationTurnGate();
        var outer = gate.tryAcquire("conv");
        gate.withPermit(outer, () -> {
            var nested = gate.tryAcquire("conv");
            assertNotNull(nested);
            nested.close();
            return null;
        });
        assertNull(gate.tryAcquire("conv"));
        outer.close();
        assertNotNull(gate.tryAcquire("conv"));
    }
}
