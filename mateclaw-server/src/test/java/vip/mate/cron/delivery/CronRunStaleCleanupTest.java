package vip.mate.cron.delivery;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class CronRunStaleCleanupTest {

    @BeforeAll
    static void initMpLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), CronJobRunEntity.class);
    }

    @Test
    void runningSweep_usesHeartbeatWithLegacyStartedAtFallback() {
        CronJobRunMapper mapper = mock(CronJobRunMapper.class);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        CronRunStaleCleanup cleanup = new CronRunStaleCleanup(mapper);

        cleanup.sweep();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        List<Wrapper> writes = captor.getAllValues();
        String runningWhere = writes.get(1).getSqlSegment();
        assertTrue(runningWhere.contains("status"), runningWhere);
        assertTrue(runningWhere.contains("COALESCE(heartbeat_at, started_at)"), runningWhere);
    }
}
