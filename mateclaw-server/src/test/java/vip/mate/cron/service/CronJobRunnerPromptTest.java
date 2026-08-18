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
        assertFalse(prompt.contains("自动投递回本任务绑定的渠道会话"),
                "web-origin runs have no channel — the auto-delivery clause must be omitted");
        assertTrue(prompt.contains("本任务未绑定渠道"),
                "non-channel runs must state that nothing is auto-delivered");
        assertTrue(prompt.contains("send_channel_message"),
                "non-channel runs must point at the channel-message tool for explicit sends");
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
                /* baseUrl */ null, null);
        String prompt = CronJobRunner.buildCronPrompt("提醒喝水", channelOrigin);

        assertTrue(prompt.contains("[定时任务执行说明]"));
        assertTrue(prompt.contains("自动投递回本任务绑定的渠道会话"),
                "channel-bound runs must keep the framework-delivery clause");
        assertTrue(prompt.contains("不要再用工具把同样的结果重复发送"),
                "the channel clause must forbid duplicate self-delivery to the bound conversation");
        assertTrue(prompt.contains("send_channel_message"),
                "cross-conversation sends must be routed through the channel-message tool");
    }

    @Test
    void nullOrigin_stillProducesContextNote() {
        String prompt = CronJobRunner.buildCronPrompt("hello", null);
        assertTrue(prompt.contains("[定时任务执行说明]"));
        assertFalse(prompt.contains("自动投递回本任务绑定的渠道会话"));
        assertTrue(prompt.endsWith("hello"));
    }
}
