package vip.mate.cron.delivery;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.DeliveryOptions;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.model.DeliveryConfig;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RFC-063r §2.6: ChannelCronResultDelivery dispatch contract.
 */
class ChannelCronResultDeliveryTest {

    private CronJobRunMapper runMapper;
    private ChannelManager channelManager;
    private ChannelCronResultDelivery strategy;

    @BeforeAll
    static void initMpLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), CronJobRunEntity.class);
    }

    @BeforeEach
    void setUp() {
        runMapper = mock(CronJobRunMapper.class);
        channelManager = mock(ChannelManager.class);
        when(runMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        strategy = new ChannelCronResultDelivery(runMapper, channelManager);
    }

    @Test
    void supports_channelIdNull_returnsFalse() {
        CronJobEntity job = new CronJobEntity();
        job.setChannelId(null);
        job.setDeliveryConfig(new DeliveryConfig("u", null, null));
        assertFalse(strategy.supports(job),
                "web-origin runs (no channelId) must not match the channel strategy");
    }

    @Test
    void supports_targetIdNull_returnsFalse() {
        CronJobEntity job = new CronJobEntity();
        job.setChannelId(9L);
        job.setDeliveryConfig(new DeliveryConfig(null, "thread-1", null));
        assertFalse(strategy.supports(job),
                "channel binding without targetId must not deliver");
    }

    @Test
    void supports_targetIdBlank_returnsFalse() {
        CronJobEntity job = new CronJobEntity();
        job.setChannelId(9L);
        job.setDeliveryConfig(new DeliveryConfig("   ", null, null));
        assertFalse(strategy.supports(job),
                "blank targetId must be treated as missing");
    }

    @Test
    void supports_channelAndTargetSet_returnsTrue() {
        CronJobEntity job = new CronJobEntity();
        job.setChannelId(9L);
        job.setDeliveryConfig(new DeliveryConfig("user-7", null, null));
        assertTrue(strategy.supports(job));
    }

    @Test
    void doDeliver_callsChannelManagerWithDeliveryOptions() {
        CronJobEntity job = new CronJobEntity();
        job.setChannelId(9L);
        job.setDeliveryConfig(new DeliveryConfig("user-7", "thread-abc", "bot-001"));
        CronJobRunEntity run = new CronJobRunEntity();
        run.setId(42L);

        DeliveryOutcome outcome = strategy.deliver(job, new AssistantMessage("Daily summary"), run);

        assertEquals(DeliveryOutcome.Status.DELIVERED, outcome.status());
        assertEquals("user-7", outcome.target());
        verify(channelManager).sendToChannel(eq(9L), eq("user-7"), any(String.class),
                argThat(opts -> "thread-abc".equals(opts.threadId())
                        && "bot-001".equals(opts.accountId())));
    }

    @Test
    void doDeliver_adapterDisabled_propagatesIllegalStateAndMarksNotDelivered() {
        CronJobEntity job = new CronJobEntity();
        job.setChannelId(9L);
        job.setDeliveryConfig(new DeliveryConfig("user-7", null, null));
        CronJobRunEntity run = new CronJobRunEntity();
        run.setId(42L);

        // Simulate channel adapter unavailable — ChannelManager throws.
        IllegalStateException disabled = new IllegalStateException("Channel not active: 9");
        doThrow(disabled).when(channelManager)
                .sendToChannel(eq(9L), eq("user-7"), any(String.class), any(DeliveryOptions.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> strategy.deliver(job, new AssistantMessage("hi"), run));
        assertSame(disabled, thrown);
        // Two updates: claim + markNotDelivered
        verify(runMapper, times(2)).update(any(), any(Wrapper.class));
    }
}
