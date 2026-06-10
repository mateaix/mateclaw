package vip.mate.skill.usage;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.skill.lifecycle.SkillLifecycleService;
import vip.mate.skill.repository.SkillUsageStatMapper;
import vip.mate.skill.runtime.model.ResolvedSkill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that recording a skill load bubbles the activity timestamp up to
 * {@code mate_skill} via the lifecycle service, so the curator's daily scan
 * sees the skill as active.
 */
@ExtendWith(MockitoExtension.class)
class SkillUsageServiceActivityBubbleTest {

    @Mock
    private SkillUsageStatMapper mapper;
    @Mock
    private SkillLifecycleService lifecycleService;

    private SkillUsageService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                SkillUsageStatEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new SkillUsageService(mapper, lifecycleService);
    }

    @Test
    void recordLoadedBubblesActivityToLifecycle() {
        ResolvedSkill skill = ResolvedSkill.builder().id(7L).name("demo").build();
        when(mapper.selectOne(any())).thenReturn(null);

        service.recordLoaded(skill, 1L, "conv-1", "SKILL.md", 100);

        verify(lifecycleService).bumpActivity(7L);
    }

    @Test
    void recordLoadedIgnoresNullSkill() {
        service.recordLoaded(null, 1L, "conv-1", "SKILL.md", 100);
        verify(lifecycleService, never()).bumpActivity(any());
    }
}
