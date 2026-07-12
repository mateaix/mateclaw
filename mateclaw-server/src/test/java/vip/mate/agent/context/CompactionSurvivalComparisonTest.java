package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * New-vs-old compaction comparison harness.
 *
 * <p>This file is intentionally written to compile against BOTH:
 * <ul>
 *   <li>{@code /data/mateclaw} — the new (post-six-moves) codebase</li>
 *   <li>{@code /data/mateclaw/mateclaw-old} — the pre-six-moves codebase</li>
 * </ul>
 *
 * <p>It only uses APIs that exist in both:
 * <ul>
 *   <li>{@code new ConversationWindowManager(null, null, null)}</li>
 *   <li>{@code mgr.softTrimToolResults(messages)}</li>
 *   <li>{@code mgr.hardClearToolResults(messages)}</li>
 *   <li>{@code mgr.prePruneForSummary(messages)}</li>
 *   <li>{@code TokenEstimator.estimateTokens(...)}</li>
 * </ul>
 *
 * <p>It does NOT use {@code isExemptTool} or {@code spillEvictToolResults}
 * (those don't exist on the old code). The same source file produces
 * DIFFERENT metrics on the two codebases — that difference IS the
 * demonstration that Move 4 works.
 *
 * <p>Each test prints a {@code [METRIC]} line to stdout in a stable
 * key=value format so the new-code run and old-code run can be diffed.
 */
class CompactionSurvivalComparisonTest {

    // ==================== markers ====================

    private static final String LOAD_SKILL_BODY_TEMPLATE =
            "[mate-skill-md]\n# skill-%d\nconstraints:\n- CONSTRAINT_MARKER_%d\n"
                    + "- Always confirm before writing\n"
                    + "- Never delete files outside /tmp\n"
                    + "- Use exactly 4-space indentation\n";

    private static final String DELEGATE_BODY_TEMPLATE =
            "[sub-agent transcript %d]\nuser: list files\nassistant: DELEGATE_MARKER_%d done\n";

    private static final String READ_FILE_BODY_TEMPLATE =
            "file content %d\n" + "x".repeat(1500) + "\nREADFILE_MARKER_%d\n";

    // ==================== helpers ====================

    private static ConversationWindowManager newManager() {
        return new ConversationWindowManager(null, null, null);
    }

    private static ToolResponseMessage trm(String toolName, String body) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-" + toolName + "-" + System.nanoTime(), toolName, body)))
                .build();
    }

    /**
     * Extract ALL text from a message, including {@link ToolResponseMessage}
     * response data (which {@code message.getText()} does NOT return).
     */
    private static String extractAllText(Message m) {
        if (m == null) return "";
        if (m instanceof ToolResponseMessage trm) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                if (r.responseData() != null) sb.append(r.responseData());
            }
            return sb.toString();
        }
        String t = m.getText();
        return t == null ? "" : t;
    }

    /**
     * Token counter that ALSO counts {@link ToolResponseMessage} response data
     * (the production {@code TokenEstimator.estimateTokens(Message)} only reads
     * {@code getText()}, which is null for tool responses).
     */
    private static int realTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += TokenEstimator.estimateTokens(extractAllText(m))
                    + 4; // PER_MESSAGE_OVERHEAD
        }
        return total;
    }

    private static int countSurvivingMarkers(List<Message> messages, String markerPrefix, int total) {
        int survived = 0;
        for (int i = 0; i < total; i++) {
            String marker = markerPrefix + i;
            boolean found = false;
            for (Message m : messages) {
                if (extractAllText(m).contains(marker)) {
                    found = true;
                    break;
                }
            }
            if (found) survived++;
        }
        return survived;
    }

    private static void runAllThreePhases(ConversationWindowManager mgr, List<Message> messages) {
        mgr.softTrimToolResults(messages);
        mgr.hardClearToolResults(messages);
        mgr.prePruneForSummary(messages);
    }

    // ==================== tests ====================

    @Test
    @DisplayName("Scenario A: 50 load_skill + 50 delegate + 50 read_file, single full compaction")
    void scenarioA_bulkSurvivalRate() {
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Load the shopping skill and run a sub-agent, then read 50 files."));

        int N = 50;
        for (int i = 0; i < N; i++) {
            messages.add(new AssistantMessage("loading skill " + i));
            messages.add(trm("load_skill", String.format(LOAD_SKILL_BODY_TEMPLATE, i, i)));
            messages.add(new AssistantMessage("delegating " + i));
            messages.add(trm("delegateToAgent", String.format(DELEGATE_BODY_TEMPLATE, i, i)));
            messages.add(new AssistantMessage("reading file " + i));
            messages.add(trm("read_file", String.format(READ_FILE_BODY_TEMPLATE, i, i)));
        }

        int tokensBefore = realTokens(messages);
        long start = System.nanoTime();
        runAllThreePhases(mgr, messages);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        int tokensAfter = realTokens(messages);

        int loadSkillSurvived = countSurvivingMarkers(messages, "CONSTRAINT_MARKER_", N);
        int delegateSurvived = countSurvivingMarkers(messages, "DELEGATE_MARKER_", N);
        int readFileSurvived = countSurvivingMarkers(messages, "READFILE_MARKER_", N);

        System.out.printf(
                "[METRIC] scenario=A load_skill_survival=%d/%d delegate_survival=%d/%d "
                        + "readfile_survival=%d/%d tokens_before=%d tokens_after=%d time_ms=%d%n",
                loadSkillSurvived, N, delegateSurvived, N, readFileSurvived, N,
                tokensBefore, tokensAfter, elapsedMs);
    }

    @Test
    @DisplayName("Scenario B: 100-round extreme compression, one load_skill pinned at the head")
    void scenarioB_hundredRoundExtremeCompression() {
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Load the shopping skill, then do 100 file-reads."));
        messages.add(new AssistantMessage("loading skill"));
        messages.add(trm("load_skill",
                "[mate-skill-md]\n# ckjia-shopping\nconstraints:\n"
                        + "- ROOT_CONSTRAINT_MARKER\n"
                        + "- Always confirm before writing\n"
                        + "- Never delete files outside /tmp\n"));

        int rounds = 100;
        int tokensConsumedTotal = 0;
        long totalTimeMs = 0;
        for (int r = 0; r < rounds; r++) {
            messages.add(new AssistantMessage("reading file " + r));
            messages.add(trm("read_file", String.format(READ_FILE_BODY_TEMPLATE, r, r)));

            int before = realTokens(messages);
            long start = System.nanoTime();
            runAllThreePhases(mgr, messages);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            int after = realTokens(messages);
            tokensConsumedTotal += (before - after);
            totalTimeMs += elapsed;
        }

        int tokensFinal = realTokens(messages);
        boolean rootSurvived = messages.stream()
                .anyMatch(m -> extractAllText(m).contains("ROOT_CONSTRAINT_MARKER"));

        System.out.printf(
                "[METRIC] scenario=B rounds=%d root_constraint_survived=%s tokens_final=%d "
                        + "tokens_consumed_by_compaction=%d total_compaction_time_ms=%d%n",
                rounds, rootSurvived, tokensFinal, tokensConsumedTotal, totalTimeMs);
    }

    @Test
    @DisplayName("Scenario C: per-tool survival rate breakdown across 20 load_skill of varying body sizes")
    void scenarioC_perToolSurvivalBreakdown() {
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Load 20 skills of varying body sizes."));

        int N = 20;
        int[] bodySizes = {100, 500, 1000, 2000, 5000};
        for (int i = 0; i < N; i++) {
            int size = bodySizes[i % bodySizes.length];
            StringBuilder body = new StringBuilder();
            body.append("[mate-skill-md]\n# skill-").append(i).append("\nconstraints:\n");
            body.append("- CONSTRAINT_MARKER_").append(i).append("\n");
            while (body.length() < size) body.append('x');
            messages.add(trm("load_skill", body.toString()));
        }

        int tokensBefore = realTokens(messages);
        long start = System.nanoTime();
        runAllThreePhases(mgr, messages);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        int tokensAfter = realTokens(messages);

        int survived = countSurvivingMarkers(messages, "CONSTRAINT_MARKER_", N);

        System.out.printf(
                "[METRIC] scenario=C load_skill_count=%d survived=%d survival_rate=%.2f "
                        + "tokens_before=%d tokens_after=%d time_ms=%d%n",
                N, survived, (survived * 100.0 / N),
                tokensBefore, tokensAfter, elapsedMs);
    }

    @Test
    @DisplayName("Scenario D: token consumption per round (steady-state)")
    void scenarioD_tokenConsumptionPerRound() {
        ConversationWindowManager mgr = newManager();
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Run a long task with one pinned skill."));
        messages.add(trm("load_skill",
                "[mate-skill-md]\n# pinned\nconstraints:\n- PINNED_MARKER\n"));

        int rounds = 30;
        StringBuilder perRound = new StringBuilder();
        for (int r = 0; r < rounds; r++) {
            messages.add(new AssistantMessage("step " + r));
            messages.add(trm("read_file", String.format(READ_FILE_BODY_TEMPLATE, r, r)));

            int before = realTokens(messages);
            long start = System.nanoTime();
            runAllThreePhases(mgr, messages);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            int after = realTokens(messages);

            if (r > 0) perRound.append(",");
            perRound.append(String.format("%d:%d:%d", r, before - after, elapsed));
        }

        boolean pinnedSurvived = messages.stream()
                .anyMatch(m -> extractAllText(m).contains("PINNED_MARKER"));

        System.out.printf(
                "[METRIC] scenario=D rounds=%d pinned_survived=%s per_round=round:tokens_consumed:time_ms{%s}%n",
                rounds, pinnedSurvived, perRound);
    }
}
