package vip.mate.agent.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.progress.ProgressLedger;
import vip.mate.agent.progress.ProgressLedgerService;
import vip.mate.agent.progress.ProgressStatus;
import vip.mate.config.ConversationWindowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Black-box test: verifies that ProgressLedger entries (pinned, auto-recorded,
 * regular) survive all four stages of ConversationWindowManager compression.
 *
 * <p>This is the key accuracy guarantee for the B-class changes:
 * <ul>
 *   <li>B2 pins skill constraints into the ledger's {@code pinned} map</li>
 *   <li>B3 stores the ledger in the DB, separate from the message list</li>
 *   <li>ReasoningNode loads the ledger fresh each turn into
 *       {@code nonHistoryPrefix}, which is NEVER touched by compaction</li>
 * </ul>
 *
 * <p>Contrast with A1 (PRUNE_EXEMPT_TOOLS): A1 tried to protect
 * {@code load_skill} tool results inside the {@code messages} list, but the
 * four-stage pipeline (Soft Trim / Hard Clear / Pre-Prune / LLM Summary) does
 * NOT honor PRUNE_EXEMPT_TOOLS — so the tool result body IS destroyed.
 * B2/B3 solves this by extracting constraints into the ledger BEFORE
 * compression can touch them.
 */
class ContextCompressionLedgerSurvivalTest {

    private InMemoryProgressLedgerService ledgerService;
    private ConversationWindowManager manager;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        ledgerService = new InMemoryProgressLedgerService();

        ConversationWindowProperties props = new ConversationWindowProperties();
        props.setFirstUserAnchorEnabled(true);
        props.setFirstUserAnchorMaxTokens(400);
        manager = new ConversationWindowManager(props, null, null);

        chatModel = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("SUMMARY_OF_COMPRESSED_HISTORY"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    // ==================== Core survival test ====================

    @Test
    void allThreeLedgerEntryTypesSurviveCompression() {
        String convId = "conv-survival";

        // 1. Pin constraints (simulating B2: ActionNode.load_skill → pinSkillConstraints)
        ledgerService.upsertPinned(convId, "pin_research_0",
                "🔒 research: Never fabricate citations", "Never fabricate citations");
        ledgerService.upsertPinned(convId, "pin_research_1",
                "🔒 research: Always cite primary sources", "Always cite primary sources");

        // 2. Auto-record tool calls (simulating B5: ActionNode.autoRecordToolCalls)
        ledgerService.upsertAutoRecorded(convId, "web_search", "web_search", "found 5 results");
        ledgerService.upsertAutoRecorded(convId, "read_file", "read_file", "read paper.pdf");
        ledgerService.upsertAutoRecorded(convId, "write_file", "write_file", "wrote draft.md");

        // 3. Regular entries (simulating LLM: progress_update)
        ledgerService.upsert(convId, "step_literature_review", "Literature Review",
                ProgressStatus.DONE, "surveyed 12 papers");
        ledgerService.upsert(convId, "step_draft_outline", "Draft Outline",
                ProgressStatus.IN_PROGRESS, "writing section 3");
        ledgerService.upsert(convId, "step_final_edit", "Final Edit",
                ProgressStatus.PENDING, null);

        // 4. Build a long message history that triggers compression
        List<Message> history = buildLongHistoryWithLoadSkill(60);

        // 5. Snapshot the ledger BEFORE compression
        ProgressLedger beforeLedger = ledgerService.load(convId);
        String beforeSnapshot = beforeLedger.renderSnapshot();
        assertThat(beforeSnapshot).contains("🔒 固定约束");
        assertThat(beforeSnapshot).contains("🔧 自动记录");
        assertThat(beforeSnapshot).contains("✅ 已完成");
        assertThat(beforeSnapshot).contains("🔄 进行中");
        assertThat(beforeSnapshot).contains("⏳ 待办");

        // 6. Run PTL compression (the most aggressive: all 4 stages)
        List<Message> compacted = manager.compactForRetry(history, chatModel, convId, 1L);

        // 7. The compacted message list should be shorter
        assertThat(compacted).hasSizeLessThan(history.size());

        // 8. CRITICAL: The ledger is unaffected — it lives in the DB, not in messages
        ProgressLedger afterLedger = ledgerService.load(convId);
        assertThat(afterLedger.pinnedEntries()).hasSize(2);
        assertThat(afterLedger.asMap())
                .containsKeys("auto_web_search", "auto_read_file", "auto_write_file",
                        "step_literature_review", "step_draft_outline", "step_final_edit");

        // 9. The rendered snapshot is identical before and after compression
        String afterSnapshot = afterLedger.renderSnapshot();
        assertThat(afterSnapshot).isEqualTo(beforeSnapshot);
    }

    // ==================== A1 gap proof: load_skill body destroyed, constraints survive ====================

    @Test
    void loadSkillBodyDestroyedByCompressionButConstraintsSurviveInLedger() {
        String convId = "conv-a1-gap";

        // B2: pin constraints from a loaded skill
        ledgerService.upsertPinned(convId, "pin_security_0",
                "🔒 security: Never run rm -rf", "Never run rm -rf");
        ledgerService.upsertPinned(convId, "pin_security_1",
                "🔒 security: Confirm before shell_exec", "Confirm before shell_exec");

        // Build a history with a load_skill tool result (>200 chars, will be pruned)
        String loadSkillResult = "SKILL.md loaded successfully. This skill provides security auditing "
                + "capabilities. " + "Constraint: Never run rm -rf. ".repeat(20)
                + "Always confirm before shell_exec. " + "More padding ".repeat(20);

        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("Load the security skill"));
        history.add(AssistantMessage.builder()
                .content("Loading security skill")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "load_skill", "{\"name\":\"security\"}")))
                .build());
        history.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "load_skill", loadSkillResult)))
                .build());
        // Pad to trigger compression
        for (int i = 0; i < 50; i++) {
            history.add(new UserMessage("Turn " + i + " — ".repeat(50) + " filler content"));
            history.add(new AssistantMessage("Response " + i + " — ".repeat(50) + " filler response"));
        }

        // Run compression
        List<Message> compacted = manager.compactForRetry(history, chatModel, convId, 1L);

        // The load_skill tool result body in messages should have been pruned/replaced
        // (A1 gap: PRUNE_EXEMPT_TOOLS is not honored by the 4-stage pipeline)
        boolean loadSkillBodySurvived = compacted.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .flatMap(trm -> trm.getResponses().stream())
                .anyMatch(r -> "load_skill".equals(r.name())
                        && r.responseData() != null
                        && r.responseData().contains("Never run rm -rf"));
        // The body was destroyed by compression (known A1 gap)
        assertThat(loadSkillBodySurvived).isFalse();

        // BUT: the constraints pinned by B2 are in the ledger, which is unaffected
        ProgressLedger ledger = ledgerService.load(convId);
        assertThat(ledger.pinnedEntries()).hasSize(2);
        assertThat(ledger.renderSnapshot()).contains("Never run rm -rf");
        assertThat(ledger.renderSnapshot()).contains("Confirm before shell_exec");
    }

    // ==================== Auto-recorded entries bounded after compression ====================

    @Test
    void autoRecordedEntriesStayBoundedAfterManyToolCallsAndCompression() {
        String convId = "conv-bounded";

        // Simulate 10 tool calls — auto-record should bound to MAX_AUTO_RECORDED
        for (int i = 0; i < 10; i++) {
            ledgerService.upsertAutoRecorded(convId, "tool_" + i, "tool_" + i, "result " + i);
        }

        // Run compression on a minimal history (compression doesn't affect ledger)
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("Do task"));
        history.add(new AssistantMessage("Done"));
        manager.compactForRetry(history, chatModel, convId, 1L);

        // Ledger still bounded
        ProgressLedger ledger = ledgerService.load(convId);
        long autoCount = ledger.asMap().keySet().stream()
                .filter(k -> k.startsWith(ProgressLedger.AUTO_RECORDED_PREFIX))
                .count();
        assertThat(autoCount).isEqualTo(ProgressLedgerService.MAX_AUTO_RECORDED);
    }

    // ==================== Multiple compression cycles ====================

    @Test
    void ledgerSurvivesMultipleCompressionCycles() {
        String convId = "conv-multi-cycle";

        // Setup all three entry types
        ledgerService.upsertPinned(convId, "pin_skill_0", "🔒 skill: Rule", "Rule");
        ledgerService.upsertAutoRecorded(convId, "read_file", "read_file", "result");
        ledgerService.upsert(convId, "step_1", "Step 1", ProgressStatus.DONE, "done");

        String snapshotBefore = ledgerService.load(convId).renderSnapshot();

        // Run compression 3 times (simulating 3 user turns with PTL)
        List<Message> history = buildLongHistoryWithLoadSkill(40);
        for (int cycle = 0; cycle < 3; cycle++) {
            manager.compactForRetry(new ArrayList<>(history), chatModel, convId, 1L);
        }

        // Ledger is still intact
        String snapshotAfter = ledgerService.load(convId).renderSnapshot();
        assertThat(snapshotAfter).isEqualTo(snapshotBefore);
    }

    // ==================== Backward compat: old flat-map JSON ====================

    @Test
    void oldFlatMapLedgerMigratesToWrapperFormatWithEmptyPinned() {
        String convId = "conv-legacy";
        // Write old-format JSON (flat map, no wrapper)
        String oldJson = "{\"step_1\":{\"key\":\"step_1\",\"label\":\"Step 1\","
                + "\"status\":\"DONE\",\"note\":\"done\",\"updatedAt\":\"2025-01-01T00:00:00Z\"}}";
        ledgerService.store.put(convId, oldJson);

        ProgressLedger ledger = ledgerService.load(convId);
        assertThat(ledger.asMap()).containsKey("step_1");
        assertThat(ledger.pinnedEntries()).isEmpty(); // migrated with empty pinned

        // After an upsert, the JSON should be in wrapper format
        ledgerService.upsert(convId, "step_2", "Step 2", ProgressStatus.PENDING, null);
        String newJson = ledgerService.store.get(convId);
        assertThat(newJson).contains("\"entries\"");
        assertThat(newJson).contains("\"pinned\"");
    }

    // ==================== Helpers ====================

    /**
     * Build a long message history that includes a load_skill call/result,
     * enough to trigger the PTL compression path.
     */
    private List<Message> buildLongHistoryWithLoadSkill(int fillerTurns) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Load the research skill and do a literature review"));

        // load_skill tool call + result
        messages.add(AssistantMessage.builder()
                .content("I'll load the research skill first")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-ls", "function", "load_skill", "{\"name\":\"research\"}")))
                .build());
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-ls", "load_skill",
                        "Skill 'research' loaded. Constraints: Never fabricate citations. "
                                + "Always cite primary sources. " + "Padding ".repeat(30))))
                .build());

        // Filler turns to build up history
        for (int i = 0; i < fillerTurns; i++) {
            messages.add(new UserMessage("Question " + i + ": " + "x".repeat(200)));
            messages.add(AssistantMessage.builder()
                    .content("Answer " + i + ": " + "y".repeat(200))
                    .toolCalls(i % 5 == 0 ? List.of(new AssistantMessage.ToolCall(
                            "call-" + i, "function", "web_search",
                            "{\"q\":\"query-" + i + "\"}")) : List.of())
                    .build());
            if (i % 5 == 0) {
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-" + i, "web_search", "Search result " + i + ": " + "z".repeat(300))))
                        .build());
            }
        }
        return messages;
    }

    // ==================== In-memory ledger service ====================

    private static final class InMemoryProgressLedgerService extends ProgressLedgerService {
        final Map<String, String> store = new ConcurrentHashMap<>();

        InMemoryProgressLedgerService() {
            super(null, new ObjectMapper().registerModule(new JavaTimeModule()));
        }

        @Override
        protected String loadLedgerJson(String conversationId) {
            return store.get(conversationId);
        }

        @Override
        protected void saveLedgerJson(String conversationId, String json) {
            store.put(conversationId, json);
        }
    }
}
