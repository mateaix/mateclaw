package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the runtime model-identity line added by the 5-arg
 * {@link RuntimeContextInjector#buildContextMessage} overload.
 *
 * <p>Unlike the sender block, the model line is about the AGENT (which
 * model is driving this run), not the caller — so it must appear for
 * every origin, including web and cron. The legacy 3/4-arg overloads
 * must stay model-free so existing eval baselines don't shift.
 */
class RuntimeContextInjectorModelTest {

    @Test
    @DisplayName("model + provider present → emits Model line with provider parenthetical + hint")
    void modelLineWithProvider() {
        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, ChatOrigin.EMPTY, "gpt-4o", "openai");

        assertTrue(ctx.contains("[system-context] Model: gpt-4o"), "model line missing: " + ctx);
        assertTrue(ctx.contains("(provider: openai)"), "provider missing: " + ctx);
        assertTrue(ctx.contains("answer with this value for the current run"),
                "model-identity hint missing: " + ctx);
    }

    @Test
    @DisplayName("blank provider → Model line without provider parenthetical")
    void modelLineWithoutProvider() {
        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, ChatOrigin.EMPTY, "claude-sonnet-4-6", "  ");

        assertTrue(ctx.contains("[system-context] Model: claude-sonnet-4-6"), "model line missing: " + ctx);
        assertFalse(ctx.contains("(provider:"), "blank provider must not emit parenthetical: " + ctx);
    }

    @Test
    @DisplayName("web origin still gets the model line (agent fact, not sender fact)")
    void webOriginStillGetsModelLine() {
        ChatOrigin origin = ChatOrigin.web("conv_1", "user-1", 5L, "/data/ws/5");

        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, origin, "gpt-4o", "openai");

        assertFalse(ctx.contains("Channel:"), "web origin must still suppress sender block: " + ctx);
        assertTrue(ctx.contains("Model: gpt-4o"), "web origin must still get model line: " + ctx);
    }

    @Test
    @DisplayName("blank modelName → no model line at all")
    void blankModelNoLine() {
        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, ChatOrigin.EMPTY, "  ", "openai");

        assertFalse(ctx.contains("Model:"), "blank model must not emit a model line: " + ctx);
    }

    @Test
    @DisplayName("IM origin → both sender block AND model line present")
    void imOriginHasSenderAndModel() {
        ChatOrigin origin = new ChatOrigin(
                7L, "feishu:oc_abc", "ou_xyz", 5L, "/data/ws/5",
                9L, null, false, "Alice", "feishu", "oc_abc", null);

        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, origin, "gpt-4o", "openai");

        assertTrue(ctx.contains("Channel: feishu"), "sender block missing: " + ctx);
        assertTrue(ctx.contains("Model: gpt-4o"), "model line missing: " + ctx);
    }
}
