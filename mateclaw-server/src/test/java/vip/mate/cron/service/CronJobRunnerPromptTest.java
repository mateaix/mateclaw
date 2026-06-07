package vip.mate.cron.service;

import org.junit.jupiter.api.Test;
import vip.mate.agent.context.ChannelTarget;
import vip.mate.agent.context.ChatOrigin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CronJobRunner#buildCronPrompt} assembles the scheduled-job prompt:
 * the execution-context note is always prepended, the channel-delivery clause
 * appears only for channel-bound runs, and the no-op sentinel instruction is
 * always present so the agent can explicitly skip a run.
 */
class CronJobRunnerPromptTest {

    @Test
    void webOriginCron_prependsContextNote_withoutDeliveryClause() {
        ChatOrigin webOrigin = ChatOrigin.web("tasks_1", "system", 1L, null);
        String input = "汇总今天的科技新闻";
        String prompt = CronJobRunner.buildCronPrompt(input, webOrigin);

        assertTrue(prompt.contains("[定时任务执行说明]"),
                "every scheduled run must carry the execution-context note");
        assertTrue(prompt.contains("隔离执行"),
                "the note must tell the model this run has no prior history");
        assertFalse(prompt.contains("投递回原渠道"),
                "web-origin runs have no channel — the delivery clause must be omitted");
        assertTrue(prompt.contains(CronJobRunner.CRON_SILENT_MARKER),
                "the no-op sentinel instruction must always be present");
        assertTrue(prompt.endsWith(input),
                "the task instruction must be the tail of the prompt");
    }

    @Test
    void channelBoundCron_addsDeliveryClause() {
        ChatOrigin channelOrigin = new ChatOrigin(
                7L, "cron_7", "system", 1L, null,
                /* channelId */ 9L, new ChannelTarget("group-a", null, null),
                /* cronOrigin */ true,
                /* senderName */ null,
                /* channelType */ "feishu",
                /* chatId */ "group-a",
                /* baseUrl */ null);
        String prompt = CronJobRunner.buildCronPrompt("提醒喝水", channelOrigin);

        assertTrue(prompt.contains("[定时任务执行说明]"));
        assertTrue(prompt.contains("投递回原渠道"),
                "channel-bound runs must keep the framework-delivery clause");
        assertTrue(prompt.contains("不要尝试调用 CLI"),
                "the channel clause must forbid CLI / send-tool hallucination");
    }

    @Test
    void nullOrigin_stillProducesContextNote() {
        String prompt = CronJobRunner.buildCronPrompt("hello", null);
        assertTrue(prompt.contains("[定时任务执行说明]"));
        assertFalse(prompt.contains("投递回原渠道"));
        assertTrue(prompt.endsWith("hello"));
    }
}
