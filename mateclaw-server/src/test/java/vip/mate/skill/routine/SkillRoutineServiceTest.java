package vip.mate.skill.routine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.skill.routine.model.SkillRoutineCandidateEntity;
import vip.mate.skill.routine.repository.SkillRoutineCandidateMapper;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRoutineServiceTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""),
                SkillRoutineCandidateEntity.class);
    }

    @Test
    void listIsWorkspaceScoped() {
        SkillRoutineCandidateMapper mapper = mock(SkillRoutineCandidateMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        SkillRoutineService service = new SkillRoutineService(
                mapper, mock(SkillRoutinePromoter.class), new SkillRoutineProperties());

        service.list(null, 20, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<SkillRoutineCandidateEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().toLowerCase().contains("workspace"));
    }

    @Test
    void mutationUsesIdAndWorkspaceRatherThanGlobalSelectById() {
        SkillRoutineCandidateMapper mapper = mock(SkillRoutineCandidateMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        SkillRoutineService service = new SkillRoutineService(
                mapper, mock(SkillRoutinePromoter.class), new SkillRoutineProperties());

        assertThrows(IllegalArgumentException.class, () -> service.dismiss(99L, 7L));

        verify(mapper, never()).selectById(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<SkillRoutineCandidateEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("id"), sql);
        assertTrue(sql.toLowerCase().contains("workspace"), sql);
    }

    @Test
    void staleEvidenceIsNotReportedAsQualified() {
        SkillRoutineCandidateMapper mapper = mock(SkillRoutineCandidateMapper.class);
        SkillRoutineCandidateEntity stale = new SkillRoutineCandidateEntity();
        stale.setOccurrenceCount(20);
        stale.setDistinctDayCount(10);
        stale.setLastSeenAt(LocalDateTime.now().minusDays(90));
        when(mapper.selectList(any())).thenReturn(List.of(stale));
        SkillRoutineService service = new SkillRoutineService(
                mapper, mock(SkillRoutinePromoter.class), new SkillRoutineProperties());

        List<Map<String, Object>> rows = service.list(null, 20, 1L);

        assertFalse((Boolean) rows.get(0).get("qualified"));
    }
}
