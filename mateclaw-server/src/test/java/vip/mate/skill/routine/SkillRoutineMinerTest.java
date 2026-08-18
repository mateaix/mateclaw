package vip.mate.skill.routine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.common.text.Shingles;
import vip.mate.skill.routine.repository.SkillRoutineCandidateMapper;
import vip.mate.skill.routine.model.SkillRoutineCandidateEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;

/**
 * Tests for the deterministic half of routine mining — opener normalization
 * and clustering. These are the parts that decide whether two runs of the same
 * habitual request are recognised as the same routine.
 */
class SkillRoutineMinerTest {

    private SkillRoutineProperties properties;
    private SkillRoutineMiner miner;
    private ConversationMapper conversationMapper;
    private SkillRoutineCandidateMapper candidateMapper;
    private WorkspaceMapper workspaceMapper;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""),
                ConversationEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""),
                SkillRoutineCandidateEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""),
                WorkspaceEntity.class);
    }

    @BeforeEach
    void setUp() {
        properties = new SkillRoutineProperties();
        conversationMapper = mock(ConversationMapper.class);
        candidateMapper = mock(SkillRoutineCandidateMapper.class);
        workspaceMapper = mock(WorkspaceMapper.class);
        miner = new SkillRoutineMiner(
                conversationMapper,
                mock(MessageMapper.class),
                candidateMapper,
                workspaceMapper,
                properties,
                new ObjectMapper());
    }

    @Test
    @DisplayName("scheduled mining applies the conversation cap independently per workspace")
    void scheduledMiningIsWorkspaceFair() {
        properties.setEnabled(true);
        WorkspaceEntity one = new WorkspaceEntity();
        one.setId(1L);
        WorkspaceEntity two = new WorkspaceEntity();
        two.setId(2L);
        when(workspaceMapper.selectList(any())).thenReturn(List.of(one, two));
        when(conversationMapper.selectPage(any(), any())).thenReturn(new Page<>());

        miner.mineAll();

        verify(conversationMapper, times(2)).selectPage(any(), any());
        verify(candidateMapper, times(2)).update(any(), any());
    }

    @Test
    @DisplayName("manual mining without a workspace fails closed to workspace 1")
    @SuppressWarnings("unchecked")
    void missingWorkspaceDoesNotWidenToAllTenants() {
        properties.setEnabled(true);
        when(conversationMapper.selectPage(any(), any())).thenReturn(new Page<>());

        miner.mine(null);

        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(conversationMapper).selectPage(any(), query.capture());
        assertTrue(query.getValue().getSqlSegment().toLowerCase().contains("workspaceid")
                        && query.getValue().getParamNameValuePairs().containsValue(1L),
                "manual mining must always add a workspace predicate: " + query.getValue().getSqlSegment());
    }

    private SkillRoutineMiner.Opener opener(String text, int dayOffset) {
        String normalized = miner.normalize(text);
        return new SkillRoutineMiner.Opener(
                "conv-" + text.hashCode() + "-" + dayOffset,
                1L, 1L, text, normalized, Shingles.of(normalized),
                LocalDateTime.of(2026, 8, 1, 9, 0).plusDays(dayOffset));
    }

    @Test
    @DisplayName("normalize strips the values that vary between runs of one routine")
    void normalizeStripsVaryingValues() {
        String a = miner.normalize("Generate the 2026-08-04 ops report");
        String b = miner.normalize("Generate the 2026-08-05 ops report");
        assertEquals(a, b, "dates must not distinguish two runs of the same routine");
    }

    @Test
    @DisplayName("normalize drops URLs and filesystem paths")
    void normalizeDropsUrlsAndPaths() {
        String n = miner.normalize("Summarize https://example.com/x and /var/log/app.log please");
        assertTrue(n.contains("summarize"), "intent words survive: " + n);
        assertTrue(!n.contains("example") && !n.contains("var"),
                "URL and path tokens must be stripped: " + n);
    }

    @Test
    @DisplayName("Chinese openers cluster without a word segmenter")
    void clustersChineseOpeners() {
        List<SkillRoutineMiner.Opener> openers = new ArrayList<>(List.of(
                opener("帮我生成今天的运维日报", 0),
                opener("帮我生成今天的运维日报，谢谢", 1),
                opener("生成今天的运维日报", 2)));

        List<SkillRoutineMiner.Cluster> clusters = miner.cluster(openers);

        assertEquals(1, clusters.size(), "three phrasings of one request must form one cluster");
        assertEquals(3, clusters.get(0).members().size());
        assertEquals(3, clusters.get(0).distinctDays());
    }

    @Test
    @DisplayName("unrelated requests stay in separate clusters")
    void keepsUnrelatedRequestsApart() {
        List<SkillRoutineMiner.Opener> openers = new ArrayList<>(List.of(
                opener("帮我生成今天的运维日报", 0),
                opener("把这段代码重构成异步实现", 1),
                opener("查一下上个季度的营收数字", 2)));

        List<SkillRoutineMiner.Cluster> clusters = miner.cluster(openers);

        assertEquals(3, clusters.size(), "distinct intents must not be merged");
    }

    @Test
    @DisplayName("English openers cluster on shared word tokens")
    void clustersEnglishOpeners() {
        List<SkillRoutineMiner.Opener> openers = new ArrayList<>(List.of(
                opener("generate the weekly oncall digest for the team", 0),
                opener("generate the weekly oncall digest for the team now", 3)));

        List<SkillRoutineMiner.Cluster> clusters = miner.cluster(openers);

        assertEquals(1, clusters.size());
        assertEquals(2, clusters.get(0).distinctDays());
    }

    @Test
    @DisplayName("distinctDays counts calendar days, not occurrences")
    void distinctDaysIgnoresSameDayRetries() {
        // Five conversations in one afternoon is one person retrying, not a habit.
        List<SkillRoutineMiner.Opener> sameDay = new ArrayList<>(List.of(
                opener("帮我生成今天的运维日报", 0),
                opener("帮我生成今天的运维日报", 0),
                opener("帮我生成今天的运维日报", 0)));

        List<SkillRoutineMiner.Cluster> clusters = miner.cluster(sameDay);

        assertEquals(1, clusters.size());
        assertEquals(3, clusters.get(0).members().size());
        assertEquals(1, clusters.get(0).distinctDays(),
                "same-day retries must not satisfy the habit gate");
    }

    @Test
    @DisplayName("a raised similarity threshold splits loosely-related openers")
    void thresholdControlsMergeAggressiveness() {
        properties.setSimilarityThreshold(0.95);
        List<SkillRoutineMiner.Opener> openers = new ArrayList<>(List.of(
                opener("generate the weekly oncall digest for the team", 0),
                opener("generate the weekly oncall digest for the team now", 1)));

        assertEquals(2, miner.cluster(openers).size());
    }
}
