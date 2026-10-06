package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.channel.feishu.FeishuChannelAdapter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.weixin.WeixinChannelAdapter;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelHealthMonitorQuietTest {
    private final ChannelManager manager=mock(ChannelManager.class);
    private final ChannelHealthMonitor monitor=new ChannelHealthMonitor(manager);
    private final ChannelMessageRouter router=mock(ChannelMessageRouter.class);
    private final ObjectMapper json=new ObjectMapper();
    private ChannelEntity entity() {
        ChannelEntity e=new ChannelEntity();e.setId(42L);e.setName("health test");e.setConfigJson("{}");return e;
    }
    private void simulateActivityAge(AbstractChannelAdapter adapter,Duration age,AbstractChannelAdapter.ConnectionState state) {
        adapter.getConnectionState().set(state);
        adapter.getLastEventTimeMs().set(System.currentTimeMillis()-age.toMillis());
        when(manager.getActiveAdapters()).thenReturn(List.of(adapter));
    }
    @Test void quietFeishuDoesNotTriggerHourlyRestart() {
        var adapter=new FeishuChannelAdapter(entity(),router,json);
        simulateActivityAge(adapter,Duration.ofHours(3),AbstractChannelAdapter.ConnectionState.CONNECTED);
        monitor.checkHealth();verify(manager,never()).restartChannel(anyLong());
        assertEquals(Duration.ZERO,adapter.stalenessThreshold());
    }
    @Test void idleExemptionStillRecoversErrorAndHonorsCooldown() {
        var adapter=new FeishuChannelAdapter(entity(),router,json);
        simulateActivityAge(adapter,Duration.ofMinutes(6),AbstractChannelAdapter.ConnectionState.ERROR);
        monitor.checkHealth();monitor.checkHealth();
        verify(manager,times(1)).restartChannel(42L);
    }
    @Test void idleExemptionDoesNotRestartRecentErrors() {
        var adapter=new FeishuChannelAdapter(entity(),router,json);
        simulateActivityAge(adapter,Duration.ofMinutes(2),AbstractChannelAdapter.ConnectionState.ERROR);
        monitor.checkHealth();verify(manager,never()).restartChannel(anyLong());
    }
    @Test void longPollStalenessStillRestartsAfterFiveMinutes() {
        // Real Weixin override, with no network startup or token configuration.
        var adapter=mock(WeixinChannelAdapter.class,withSettings().useConstructor(entity(),router,json).defaultAnswer(CALLS_REAL_METHODS));
        assertEquals(Duration.ofMinutes(5),adapter.stalenessThreshold());
        simulateActivityAge(adapter,Duration.ofMinutes(6),AbstractChannelAdapter.ConnectionState.CONNECTED);
        monitor.checkHealth();verify(manager).restartChannel(42L);
    }
}
