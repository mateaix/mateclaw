package vip.mate.channel.feishu.cards;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.approval.ApprovalService;
import vip.mate.channel.feishu.cards.tool_guard.ToolGuardButtonValue;
import vip.mate.channel.feishu.cards.tool_guard.ToolGuardCardKindFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pin the dispatcher's registration + lookup invariants.
 */
class FeishuCardDispatcherTest {

    private FeishuCardDispatcher newDispatcher() {
        ToolGuardCardKindFactory factory = new ToolGuardCardKindFactory(
                mock(ApprovalService.class),
                new ObjectMapper());
        return new FeishuCardDispatcher(factory);
    }

    @Test
    @DisplayName("tool_guard kind is registered after construction")
    void toolGuardRegistered() {
        FeishuCardDispatcher d = newDispatcher();
        assertTrue(d.registeredKindNames().contains(ToolGuardCardKindFactory.KIND_NAME));
        assertEquals(1, d.registeredKindNames().size());
    }

    @Test
    @DisplayName("lookupByName returns the registered kind")
    void lookupByName() {
        FeishuCardDispatcher d = newDispatcher();
        Optional<FeishuCardKind> opt = d.lookupByName(ToolGuardCardKindFactory.KIND_NAME);
        assertTrue(opt.isPresent());
        assertEquals(ToolGuardCardKindFactory.KIND_NAME, opt.get().name());

        assertFalse(d.lookupByName("nonexistent").isPresent());
        assertFalse(d.lookupByName(null).isPresent());
        assertFalse(d.lookupByName("").isPresent());
    }

    @Test
    @DisplayName("lookupByAction matches the prefix and ignores unknown actions")
    void lookupByAction() {
        FeishuCardDispatcher d = newDispatcher();
        Optional<FeishuCardKind> approve = d.lookupByAction(ToolGuardButtonValue.ACTION_APPROVE);
        assertTrue(approve.isPresent());
        Optional<FeishuCardKind> deny = d.lookupByAction(ToolGuardButtonValue.ACTION_DENY);
        assertTrue(deny.isPresent());
        // Any string starting with the prefix matches
        assertTrue(d.lookupByAction("tg_approval.future_subaction").isPresent());

        assertFalse(d.lookupByAction("unknown.action").isPresent());
        assertFalse(d.lookupByAction(null).isPresent());
        assertFalse(d.lookupByAction("").isPresent());
    }

    @Test
    @DisplayName("FeishuCardKind constructor rejects blank name / prefix")
    void cardKindValidation() {
        FeishuCardKind valid = new FeishuCardKind(
                "ok", "ok.", (n) -> java.util.Map.of(), (adapter, data) -> null);
        assertEquals("ok", valid.name());

        assertThrows(IllegalArgumentException.class,
                () -> new FeishuCardKind("", "x.", (n) -> java.util.Map.of(), (a, d) -> null));
        assertThrows(IllegalArgumentException.class,
                () -> new FeishuCardKind("ok", " ", (n) -> java.util.Map.of(), (a, d) -> null));
    }
}
