package vip.mate.agent.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.progress.ProgressLedger;
import vip.mate.agent.progress.ProgressLedgerService;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.ActionExecutionLedger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the auto-record skip list in {@link ActionNode#autoRecordToolCalls}:
 * read-only / status-query tools must NOT be written to the ledger as DONE
 * steps. A DONE marker plus the snapshot's "已完成的步骤不要重复执行"
 * instruction stops the agent from re-querying live state when the user
 * repeats a question, so only mutating/task-shaped tool calls belong in
 * the auto-recorded section.
 */
class ActionNodeAutoRecordSkipTest {

    /** In-memory ledger double — same pattern as the service-level tests. */
    private static final class InMemoryProgressLedgerService extends ProgressLedgerService {
        private final Map<String, String> store = new ConcurrentHashMap<>();

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

    private static ToolResponseMessage.ToolResponse resp(String name, String data) {
        return new ToolResponseMessage.ToolResponse("id-" + name, name, data);
    }

    private static ActionNode nodeWith(ProgressLedgerService ledgerService) {
        ActionNode node = new ActionNode(null);
        node.setProgressLedgerService(ledgerService);
        return node;
    }

    @Test
    @DisplayName("read-only / status-query tools are never auto-recorded")
    void readOnlyToolsSkipped() {
        InMemoryProgressLedgerService ledger = new InMemoryProgressLedgerService();
        ActionNode node = nodeWith(ledger);
        String conv = "conv-skip-1";

        node.autoRecordToolCalls(conv, List.of(
                resp("read_file", "{\"content\":\"...\"}"),
                resp("web_search", "results..."),
                resp("extract_document_text", "text..."),
                resp("extract_pdf_text", "text..."),
                resp("extract_docx_text", "text..."),
                resp("detect_file_type", "application/pdf"),
                resp("getCurrentDateTime", "2026-07-31T10:33:00"),
                resp("getCurrentDate", "2026-07-31"),
                resp("getCurrentTime", "10:33"),
                resp("listSubagents", "[]")));

        assertTrue(ledger.load(conv).isEmpty(),
                "no read-only tool call may produce a ledger entry");
    }

    @Test
    @DisplayName("mutating tools are still auto-recorded alongside skipped read-only ones")
    void mutatingToolsStillRecorded() {
        InMemoryProgressLedgerService ledger = new InMemoryProgressLedgerService();
        ActionNode node = nodeWith(ledger);
        String conv = "conv-skip-2";

        node.autoRecordToolCalls(conv, List.of(
                resp("read_file", "{\"content\":\"...\"}"),
                resp("write_file", "written"),
                resp("mcp_home_check_room_a1b2c3", "0人")));

        ProgressLedger loaded = ledger.load(conv);
        Set<String> keys = loaded.asMap().keySet();
        assertEquals(2, keys.size(), "exactly the non-read-only calls are recorded; got " + keys);
        assertTrue(keys.contains(ProgressLedger.AUTO_RECORDED_PREFIX + "write_file"));
        assertTrue(keys.contains(ProgressLedger.AUTO_RECORDED_PREFIX + "mcp_home_check_room_a1b2c3"));
    }

    @Test
    @DisplayName("meta-tools (load_skill / enable_tool / progress_update / skill helpers) stay skipped")
    void metaToolsStillSkipped() {
        InMemoryProgressLedgerService ledger = new InMemoryProgressLedgerService();
        ActionNode node = nodeWith(ledger);
        String conv = "conv-skip-3";

        node.autoRecordToolCalls(conv, List.of(
                resp("load_skill", "loaded"),
                resp("enable_tool", "enabled"),
                resp("progress_update", "ok"),
                resp("listAvailableSkills", "[]"),
                resp("readSkillFile", "..."),
                resp("runSkillScript", "...")));

        assertTrue(ledger.load(conv).isEmpty());
    }

    @Test
    @DisplayName("failed mutating tools are not auto-recorded as completed progress")
    void failedMutationIsNotRecorded() {
        InMemoryProgressLedgerService ledger = new InMemoryProgressLedgerService();
        ActionNode node = nodeWith(ledger);
        ToolResponseMessage.ToolResponse response = resp("schedule_meeting", "HTTP 500");
        ActionExecutionLedger receipts = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete(response.id(), response.name(), response.responseData(), false)));

        node.autoRecordToolCalls("conv-failed", List.of(response), receipts);

        assertTrue(ledger.load("conv-failed").isEmpty());
    }
}
