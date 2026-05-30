package vip.mate.wiki.pipeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiPipelineDefinitionEntity;
import vip.mate.wiki.model.WikiPipelineRunEntity;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiPipelineDefinitionMapper;
import vip.mate.wiki.repository.WikiPipelineRunMapper;
import vip.mate.wiki.repository.WikiPipelineStepRunMapper;
import vip.mate.wiki.service.WikiPageService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end test of the count-threshold trigger against H2: a pipeline fires
 * once the page count reaches the threshold, is deduplicated within the same
 * threshold bucket, and fires again at the next bucket.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiPipelineTriggerServiceE2ETest {

    private static final WikiStepExecutor NOOP = new WikiStepExecutor() {
        public String type() { return "noop"; }
        public String execute(WikiStepContext c) { return "ok"; }
    };

    @Autowired private WikiPipelineDefinitionMapper definitionMapper;
    @Autowired private WikiPipelineRunMapper runMapper;
    @Autowired private WikiPipelineStepRunMapper stepRunMapper;
    @Autowired private WikiPageMapper pageMapper;
    @Autowired private WikiPageService pageService;
    @Autowired private ObjectMapper objectMapper;

    private WikiPipelineTriggerService triggerService;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    @BeforeEach
    void setUp() {
        WikiPipelineService pipelineService = new WikiPipelineService(
                runMapper, stepRunMapper, objectMapper, List.of(NOOP));
        triggerService = new WikiPipelineTriggerService(
                definitionMapper, pipelineService, pageMapper, objectMapper);
    }

    private long seedDefinition(long kb, int threshold) {
        WikiPipelineDefinitionEntity d = new WikiPipelineDefinitionEntity();
        d.setKbId(kb);
        d.setName("episode-to-pattern-" + SEQ.incrementAndGet());
        d.setOwnerAgentId(42L);
        d.setTriggerType("page_type_count");
        d.setTriggerConfigJson("{\"page_type\":\"episode\",\"threshold\":" + threshold + "}");
        d.setStepsJson("[{\"id\":\"s\",\"executor\":\"noop\"}]");
        d.setEnabled(1);
        d.setCreateTime(LocalDateTime.now());
        d.setUpdateTime(LocalDateTime.now());
        definitionMapper.insert(d);
        return d.getId();
    }

    private void addEpisodes(long kb, int n) {
        for (int i = 0; i < n; i++) {
            pageService.createPage(kb, "ep-" + kb + "-" + SEQ.incrementAndGet(),
                    "Episode", "body", "s", "[1]", "episode");
        }
    }

    private long runCount(long defId) {
        return runMapper.selectCount(Wrappers.<WikiPipelineRunEntity>lambdaQuery()
                .eq(WikiPipelineRunEntity::getDefinitionId, defId));
    }

    @Test
    void firesAtThreshold_dedupsWithinBucket_firesAtNextBucket() {
        long kb = SEQ.incrementAndGet();
        long defId = seedDefinition(kb, 3);

        // Below threshold: no run.
        addEpisodes(kb, 2);
        assertEquals(0, triggerService.onPageTypeCount(kb, "episode"));
        assertEquals(0, runCount(defId));

        // Reaching threshold (3) fires one run (bucket 1).
        addEpisodes(kb, 1);
        assertEquals(1, triggerService.onPageTypeCount(kb, "episode"));
        assertEquals(1, runCount(defId));

        // Still in bucket 1 (count 4): deduped, no new run.
        addEpisodes(kb, 1);
        assertEquals(0, triggerService.onPageTypeCount(kb, "episode"));
        assertEquals(1, runCount(defId));

        // Crossing into bucket 2 (count 6) fires again.
        addEpisodes(kb, 2);
        assertEquals(1, triggerService.onPageTypeCount(kb, "episode"));
        assertEquals(2, runCount(defId));
    }

    @Test
    void nonMatchingPageType_doesNotFire() {
        long kb = SEQ.incrementAndGet();
        long defId = seedDefinition(kb, 1);
        // A different pageType event must not trigger the episode pipeline.
        assertEquals(0, triggerService.onPageTypeCount(kb, "concept"));
        assertEquals(0, runCount(defId));
    }
}
