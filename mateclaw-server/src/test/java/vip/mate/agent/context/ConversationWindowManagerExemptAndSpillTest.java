package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.agent.graph.executor.ToolResultStorage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Move 4 — behavioral tests that would FAIL on the pre-Move-4 code.
 *
 * <p>Pre-Move-4 bugs being verified:
 * <ol>
 *   <li>{@code softTrimToolResults}, {@code hardClearToolResults}, and
 *       {@code prePruneForSummary} did NOT check {@code PRUNE_EXEMPT_TOOLS}.
 *       A {@code load_skill} or {@code delegateToAgent} result would be
 *       trimmed/cleared/pruned, silently dropping the skill's constraints
 *       or the sub-agent's transcript.</li>
 *   <li>There was no lossless spill-evict path before the LLM summary —
 *       every over-budget conversation paid the LLM-summary token cost
 *       even when spilling to disk would have sufficed.</li>
 * </ol>
 *
 * <p>Each test below asserts the NEW behavior. To confirm they would fail
 * on the old code, revert the Move 4 changes in ConversationWindowManager
 * and re-run — every test in this class should fail.
 */
class ConversationWindowManagerExemptAndSpillTest {

    // Reuse the same constants the production code uses.
    private static final String LOAD_SKILL_BODY =
            "[mate-skill-md]\n# ckjia-shopping\nconstraints:\n- Always confirm before writing\n";
    private static final String DELEGATE_BODY =
            "[sub-agent transcript]\nuser: list files\nassistant: ...";
    private static final String READ_FILE_BODY = "x".repeat(2000);

    private static ConversationWindowManager newManager() {
        // Constructor: (ConversationWindowProperties, MemoryManager, ConversationService)
        // — all can be null for the phases we test (softTrim/hardClear/prePrune
        // don't touch memory or conversation service).
        return new ConversationWindowManager(null, null, null);
    }

    private static ToolResponseMessage trm(String toolName, String body) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-" + toolName, toolName, body)))
                .build();
    }

    // ==================== Move 4.1: isExemptTool ====================

    @Test
    @DisplayName("isExemptTool(load_skill) → true (NEW: method did not exist pre-Move-4)")
    void loadSkillIsExempt() {
        ToolResponseMessage.ToolResponse r = new ToolResponseMessage.ToolResponse(
                "id", "load_skill", "body");
        assertTrue(ConversationWindowManager.isExemptTool(r),
                "load_skill must be exempt — pre-Move-4 this method did not exist");
    }

    @Test
    @DisplayName("isExemptTool(delegateToAgent) → true")
    void delegateIsExempt() {
        ToolResponseMessage.ToolResponse r = new ToolResponseMessage.ToolResponse(
                "id", "delegateToAgent", "body");
        assertTrue(ConversationWindowManager.isExemptTool(r));
    }

    @Test
    @DisplayName("isExemptTool(delegateParallel) → true")
    void delegateParallelIsExempt() {
        ToolResponseMessage.ToolResponse r = new ToolResponseMessage.ToolResponse(
                "id", "delegateParallel", "body");
        assertTrue(ConversationWindowManager.isExemptTool(r));
    }

    @Test
    @DisplayName("isExemptTool(read_file) → false (non-exempt tool)")
    void readFileIsNotExempt() {
        ToolResponseMessage.ToolResponse r = new ToolResponseMessage.ToolResponse(
                "id", "read_file", "body");
        assertFalse(ConversationWindowManager.isExemptTool(r));
    }

    @Test
    @DisplayName("isExemptTool(null) → false (defensive)")
    void nullIsNotExempt() {
        assertFalse(ConversationWindowManager.isExemptTool(null));
    }

    // ==================== Move 4.2: softTrimToolResults preserves exempt ====================

    @Test
    @DisplayName("softTrimToolResults preserves load_skill body verbatim (would FAIL pre-Move-4)")
    void softTrimPreservesLoadSkillBody() {
        // Pre-Move-4: softTrimToolResults only checked isSpillMarker —
        // load_skill body would be truncated to ~400 chars, destroying
        // the skill constraints. Move 4 adds isExemptTool check.
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>(List.of(
                trm("load_skill", LOAD_SKILL_BODY),
                trm("read_file", READ_FILE_BODY)));

        int trimmed = mgr.softTrimToolResults(messages);

        // read_file WAS trimmed (non-exempt)
        assertTrue(trimmed >= 1, "non-exempt read_file should be trimmed");
        // load_skill body is UNCHANGED
        ToolResponseMessage loadSkillTrm = (ToolResponseMessage) messages.get(0);
        assertEquals(LOAD_SKILL_BODY, loadSkillTrm.getResponses().get(0).responseData(),
                "load_skill body must survive softTrim verbatim — "
                        + "pre-Move-4 this would have been truncated");
        assertTrue(loadSkillTrm.getResponses().get(0).responseData().contains(
                "Always confirm before writing"),
                "constraint text must survive — pre-Move-4 it was lost");
    }

    @Test
    @DisplayName("softTrimToolResults preserves delegateToAgent body verbatim")
    void softTrimPreservesDelegateBody() {
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>(List.of(
                trm("delegateToAgent", DELEGATE_BODY)));

        mgr.softTrimToolResults(messages);

        ToolResponseMessage trm = (ToolResponseMessage) messages.get(0);
        assertEquals(DELEGATE_BODY, trm.getResponses().get(0).responseData(),
                "delegateToAgent body must survive softTrim verbatim");
    }

    // ==================== Move 4.3: hardClearToolResults preserves exempt ====================

    @Test
    @DisplayName("hardClearToolResults preserves load_skill body verbatim (would FAIL pre-Move-4)")
    void hardClearPreservesLoadSkillBody() {
        // Pre-Move-4: hardClearToolResults only checked isSpillMarker —
        // load_skill body would be replaced with "[旧工具输出已清理]".
        // This is the most destructive bypass: Phase 2 wipes the entire
        // skill constraints, then Phase 3 LLM summary can't reconstruct
        // them because they're already gone.
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>(List.of(
                trm("load_skill", LOAD_SKILL_BODY),
                trm("read_file", READ_FILE_BODY)));

        int cleared = mgr.hardClearToolResults(messages);

        // read_file WAS cleared (non-exempt)
        assertTrue(cleared >= 1, "non-exempt read_file should be cleared");
        // load_skill body is UNCHANGED
        ToolResponseMessage loadSkillTrm = (ToolResponseMessage) messages.get(0);
        assertEquals(LOAD_SKILL_BODY, loadSkillTrm.getResponses().get(0).responseData(),
                "load_skill body must survive hardClear verbatim — "
                        + "pre-Move-4 this would have been replaced with a placeholder");
        assertTrue(loadSkillTrm.getResponses().get(0).responseData().contains(
                "Always confirm before writing"),
                "constraint text must survive hardClear");
    }

    @Test
    @DisplayName("hardClearToolResults preserves delegateToAgent body verbatim")
    void hardClearPreservesDelegateBody() {
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>(List.of(
                trm("delegateToAgent", DELEGATE_BODY)));

        mgr.hardClearToolResults(messages);

        ToolResponseMessage trm = (ToolResponseMessage) messages.get(0);
        assertEquals(DELEGATE_BODY, trm.getResponses().get(0).responseData(),
                "delegateToAgent body must survive hardClear verbatim");
    }

    // ==================== Move 4.4: prePruneForSummary preserves exempt ====================

    @Test
    @DisplayName("prePruneForSummary preserves load_skill body verbatim (would FAIL pre-Move-4)")
    void prePrunePreservesLoadSkillBody() {
        // Pre-Move-4: prePruneForSummary replaced ANY non-spill body >200 chars
        // with "[旧工具输出已清理以节省上下文空间]". load_skill's SKILL.md
        // snapshot is typically >200 chars, so it was ALWAYS pruned here.
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>(List.of(
                trm("load_skill", LOAD_SKILL_BODY),
                trm("read_file", READ_FILE_BODY)));

        int pruned = mgr.prePruneForSummary(messages);

        // read_file WAS pruned (non-exempt, >200 chars)
        assertTrue(pruned >= 1, "non-exempt read_file should be pruned");
        // load_skill body is UNCHANGED
        ToolResponseMessage loadSkillTrm = (ToolResponseMessage) messages.get(0);
        assertEquals(LOAD_SKILL_BODY, loadSkillTrm.getResponses().get(0).responseData(),
                "load_skill body must survive prePrune verbatim — "
                        + "pre-Move-4 this would have been replaced with a placeholder");
    }

    @Test
    @DisplayName("prePruneForSummary skips ToolResponseMessage entirely when all responses are exempt")
    void prePruneSkipsAllExemptMessage() {
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>(List.of(
                trm("load_skill", LOAD_SKILL_BODY)));

        int pruned = mgr.prePruneForSummary(messages);

        assertEquals(0, pruned,
                "a ToolResponseMessage with only exempt responses must not be pruned at all");
    }

    // ==================== Move 4.5: spillEvictToolResults (new method) ====================

    @Test
    @DisplayName("spillEvictToolResults returns 0 when toolResultStorage is null (defensive)")
    void spillEvictNoStorage() {
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>(List.of(
                trm("read_file", READ_FILE_BODY)));
        int spilled = mgr.spillEvictToolResults(messages, "conv-1", "/tmp/ws");
        assertEquals(0, spilled, "null toolResultStorage must no-op");
    }

    @Test
    @DisplayName("spillEvictToolResults returns 0 when conversationId is null")
    void spillEvictNoConversationId() {
        // Even with storage wired, null conversationId must no-op to avoid
        // writing spill files to a meaningless path.
        ConversationWindowManager mgr = newManagerWithStorage(mock(ToolResultStorage.class));
        List<Message> messages = new ArrayList<>(List.of(
                trm("read_file", READ_FILE_BODY)));
        int spilled = mgr.spillEvictToolResults(messages, null, "/tmp/ws");
        assertEquals(0, spilled);
    }

    @Test
    @DisplayName("spillEvictToolResults skips exempt tools (load_skill not spilled)")
    void spillEvictSkipsExempt() {
        // Exempt tools must never be spilled — their content is not
        // safely recoverable (load_skill returns a snapshot that may
        // have been edited since).
        ToolResultStorage storage = mock(ToolResultStorage.class);
        ConversationWindowManager mgr = newManagerWithStorage(storage);
        List<Message> messages = new ArrayList<>(List.of(
                trm("load_skill", LOAD_SKILL_BODY)));

        int spilled = mgr.spillEvictToolResults(messages, "conv-1", "/tmp/ws");

        assertEquals(0, spilled, "load_skill must not be spilled");
    }

    // ==================== Move 4.6: Phase 2.7 integration ====================

    @Test
    @DisplayName("Move 4 invariant: exempt tools survive ALL three phases unmodified")
    void exemptToolsSurviveAllPhases() {
        // This is the integration test: run softTrim → hardClear → prePrune
        // in sequence (same order as compactMessages) and verify the
        // load_skill body is still intact at the end.
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("load the shopping skill"),
                trm("load_skill", LOAD_SKILL_BODY),
                new AssistantMessage("Now let me read a file"),
                trm("read_file", READ_FILE_BODY)));

        mgr.softTrimToolResults(messages);
        mgr.hardClearToolResults(messages);
        mgr.prePruneForSummary(messages);

        ToolResponseMessage loadSkillTrm = (ToolResponseMessage) messages.get(1);
        assertEquals(LOAD_SKILL_BODY, loadSkillTrm.getResponses().get(0).responseData(),
                "load_skill body must survive ALL three phases — "
                        + "this is the core Move 4 fix for 'compression causes attention failure'");

        // read_file WAS modified (cleared to placeholder)
        ToolResponseMessage readFileTrm = (ToolResponseMessage) messages.get(3);
        assertFalse(readFileTrm.getResponses().get(0).responseData().equals(READ_FILE_BODY),
                "non-exempt read_file should have been modified by at least one phase");
    }

    // ==================== helpers ====================

    private static ConversationWindowManager newManagerWithStorage(ToolResultStorage storage) {
        ConversationWindowManager mgr = new ConversationWindowManager(null, null, null);
        mgr.setToolResultStorage(storage);
        return mgr;
    }
}
