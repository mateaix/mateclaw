package vip.mate.agent.graph.plan.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PlanGenerationNode#displayGoal} — the scrubber that recovers the
 * user's actual request from the fully-assembled agent prompt before it is
 * persisted as the plan goal.
 *
 * <p>The graph receives the goal already enriched: a {@code <memory-context>}
 * recall block is prepended every turn, scheduled runs wrap the instruction in a
 * preamble whose payload follows {@code [任务指令]}, and a re-plan pass appends a
 * {@code [Follow-up guidance]} block. Persisting that verbatim left the Plan
 * board showing "&lt;memory-context&gt; The following is what you…" instead of the
 * task — these tests lock the clean-up.
 */
@DisplayName("PlanGeneration displayGoal scrubber")
class PlanGenerationDisplayGoalTest {

    private static final String MEMORY_WRAPPER =
            "<memory-context>\n"
            + "The following is what you already know about this user.\n"
            + "## preferred_answer_style\n用户喜欢简洁、分点的回答方式。\n"
            + "</memory-context>\n\n";

    @Test
    @DisplayName("strips the injected memory-context block")
    void stripsMemoryContext() {
        assertEquals("帮我读取 pom.xml 并总结依赖",
                PlanGenerationNode.displayGoal(MEMORY_WRAPPER + "帮我读取 pom.xml 并总结依赖"));
    }

    @Test
    @DisplayName("keeps only the instruction body of a scheduled-run wrapper")
    void unwrapsScheduledRunPrompt() {
        String cron = MEMORY_WRAPPER
                + "[定时任务执行说明]\n本次对话由定时任务自动触发，不是用户实时发来的消息。\n"
                + "- 请把下面的「任务指令」当作一个完整、独立的任务来执行。\n\n"
                + "[任务指令]\nqwen3-max 重试测试";
        assertEquals("qwen3-max 重试测试", PlanGenerationNode.displayGoal(cron));
    }

    @Test
    @DisplayName("drops the trailing follow-up guidance block")
    void dropsFollowupSuffix() {
        String withFollowup = MEMORY_WRAPPER
                + "整理本周的项目进展\n\n[Follow-up guidance]\n再补充一下风险项";
        assertEquals("整理本周的项目进展", PlanGenerationNode.displayGoal(withFollowup));
    }

    @Test
    @DisplayName("passes through a clean goal untouched")
    void passesThroughCleanGoal() {
        assertEquals("无包装直接问", PlanGenerationNode.displayGoal("无包装直接问"));
    }

    @Test
    @DisplayName("falls back to the raw goal when scrubbing leaves nothing")
    void fallsBackWhenEmpty() {
        // A goal that is nothing but the recall block must not collapse to "".
        String result = PlanGenerationNode.displayGoal(MEMORY_WRAPPER);
        assertTrue(result.contains("memory-context"),
                "scrubbing an all-wrapper goal should fall back to the raw text, not empty");
    }

    @Test
    @DisplayName("null / blank safe")
    void nullSafe() {
        assertEquals("", PlanGenerationNode.displayGoal(null));
        assertEquals("   ", PlanGenerationNode.displayGoal("   "));
    }
}
