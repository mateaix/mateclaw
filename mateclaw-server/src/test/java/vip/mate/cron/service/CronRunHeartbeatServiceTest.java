package vip.mate.cron.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CronRunHeartbeatServiceTest {

    @BeforeAll
    static void initMpLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), CronJobRunEntity.class);
    }

    @Test
    void begin_schedulesFencedHeartbeat_andLeaseClosesIdempotently() {
        CronJobRunMapper mapper = mock(CronJobRunMapper.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler)
                .scheduleAtFixedRate(task.capture(), eq(30_000L), eq(30_000L), eq(TimeUnit.MILLISECONDS));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T08:00:00Z"), ZoneOffset.UTC);
        CronRunHeartbeatService service = new CronRunHeartbeatService(
                mapper, scheduler, Duration.ofSeconds(30), clock, false);

        CronRunHeartbeatService.Lease lease = service.begin(42L);
        task.getValue().run();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
        Set<Object> values = Set.copyOf(wrapper.getParamNameValuePairs().values());
        String where = wrapper.getSqlSegment();
        assertTrue(where.contains("id"), () -> "heartbeat must target one run: " + where);
        assertTrue(where.contains("status"), () -> "heartbeat must not revive a terminal run: " + where);
        assertTrue(values.contains(LocalDateTime.of(2026, 9, 2, 8, 0)),
                () -> "missing fixed heartbeat timestamp in " + values);

        lease.close();
        lease.close();
        verify(future, times(1)).cancel(false);
    }

    @Test
    void heartbeatFailure_doesNotKillSchedulerTask() {
        CronJobRunMapper mapper = mock(CronJobRunMapper.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler)
                .scheduleAtFixedRate(task.capture(), anyLong(), anyLong(), any());
        when(mapper.update(isNull(), any(Wrapper.class))).thenThrow(new IllegalStateException("db jitter"));
        CronRunHeartbeatService service = new CronRunHeartbeatService(
                mapper, scheduler, Duration.ofSeconds(30), Clock.systemUTC(), false);

        service.begin(7L);

        assertDoesNotThrow(() -> task.getValue().run());
    }
}
