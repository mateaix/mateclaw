package vip.mate.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiAgentPageTypePermissionEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiPipelineDefinitionEntity;
import vip.mate.wiki.pipeline.WikiPipelineService;
import vip.mate.wiki.pipeline.WikiPipelineTriggerService;
import vip.mate.wiki.pipeline.WikiStepContext;
import vip.mate.wiki.pipeline.WikiStepExecutor;
import vip.mate.wiki.profile.WikiPageTypeProfile;
import vip.mate.wiki.profile.WikiPageTypeProfileService;
import vip.mate.wiki.repository.WikiAgentPageTypePermissionMapper;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiPipelineDefinitionMapper;
import vip.mate.wiki.repository.WikiPipelineRunMapper;
import vip.mate.wiki.repository.WikiPipelineStepRunMapper;
import vip.mate.wiki.service.WikiDependencyService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiPageTypePermissionService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-RFC end-to-end scenario exercised against H2 with no external model:
 * a KB defines a custom pageType profile (layered fact/experience), pages are
 * created, an agent's pageType read permission filters them, an experience
 * page depends on a fact page and goes stale when the fact changes, and a
 * count-threshold pipeline fires once the fact pages accumulate.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiDomainIntegrationE2ETest {

    private static final WikiStepExecutor NOOP = new WikiStepExecutor() {
        public String type() { return "noop"; }
        public String execute(WikiStepContext c) { return "ok"; }
    };

    @Autowired private WikiPageTypeProfileService profileService;
    @Autowired private WikiPageService pageService;
    @Autowired private WikiPageTypePermissionService permissionService;
    @Autowired private WikiAgentPageTypePermissionMapper permissionMapper;
    @Autowired private WikiDependencyService dependencyService;
    @Autowired private WikiPipelineDefinitionMapper definitionMapper;
    @Autowired private WikiPipelineRunMapper runMapper;
    @Autowired private WikiPipelineStepRunMapper stepRunMapper;
    @Autowired private WikiPageMapper pageMapper;
    @Autowired private ObjectMapper objectMapper;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    @Test
    void fullDomainFlow() {
        long kb = SEQ.incrementAndGet();
        long agent = SEQ.incrementAndGet();

        // --- RFC-56: a custom KB profile with layered page types ---
        profileService.saveProfile(kb, "liquidity",
                "{\"version\":1,\"pageTypes\":{"
                + "\"episode\":{\"label\":\"Episode\",\"layer\":\"fact\"},"
                + "\"pattern\":{\"label\":\"Pattern\",\"layer\":\"experience\"}}}");
        WikiPageTypeProfile profile = profileService.resolveProfile(kb);
        assertTrue(profile.hasPageType("episode"));
        assertTrue(profile.hasPageType("pattern"));

        // --- create pages: two fact episodes + one experience pattern ---
        WikiPageEntity ep1 = createPage(kb, "ep1-" + kb, "episode", "fact");
        WikiPageEntity ep2 = createPage(kb, "ep2-" + kb, "episode", "fact");
        WikiPageEntity pat = createPage(kb, "pat-" + kb, "pattern", "experience");

        // --- RFC-58: agent may read episode (fact) but not pattern ---
        permissionMapper.insert(perm(agent, kb, "episode", 1));
        permissionMapper.insert(perm(agent, kb, "pattern", 0));
        WikiPageTypePermissionService.Access access = permissionService.resolve(agent, kb);
        assertTrue(access.canRead("episode"));
        assertFalse(access.canRead("pattern"));

        // --- RFC-57: pattern depends on the episodes; updating one marks it stale ---
        List<String> rejected = dependencyService.setDependencies(kb, pat.getId(),
                List.of(ep1.getId(), ep2.getId()));
        assertTrue(rejected.isEmpty(), () -> "unexpected rejections: " + rejected);
        int marked = dependencyService.markDependentsStale(kb, ep1.getId(), "episode revised");
        assertEquals(1, marked);
        assertEquals(1, pageService.getBySlug(kb, "pat-" + kb).getStale());

        // --- RFC-60: a pipeline fires once 2 episodes exist (threshold 2) ---
        long defId = seedPipeline(kb);
        WikiPipelineService pipelineService = new WikiPipelineService(
                runMapper, stepRunMapper, objectMapper, List.of(NOOP));
        WikiPipelineTriggerService trigger = new WikiPipelineTriggerService(
                definitionMapper, pipelineService, pageMapper, objectMapper);

        int started = trigger.onPageTypeCount(kb, "episode");
        assertEquals(1, started, "pipeline should fire once the episode count reaches the threshold");
        long runs = runMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<vip.mate.wiki.model.WikiPipelineRunEntity>lambdaQuery()
                .eq(vip.mate.wiki.model.WikiPipelineRunEntity::getDefinitionId, defId));
        assertEquals(1, runs);

        // Re-evaluating in the same bucket does not double-fire (idempotent).
        assertEquals(0, trigger.onPageTypeCount(kb, "episode"));
    }

    private WikiPageEntity createPage(long kb, String slug, String pageType, String layer) {
        WikiPageEntity p = pageService.createPage(kb, slug, slug, "body", "s", "[1]", pageType);
        pageService.setLayerAndDependencies(p.getId(), layer, null);
        return pageService.getBySlug(kb, slug);
    }

    private WikiAgentPageTypePermissionEntity perm(long agent, long kb, String type, int canRead) {
        WikiAgentPageTypePermissionEntity e = new WikiAgentPageTypePermissionEntity();
        e.setAgentId(agent);
        e.setKbId(kb);
        e.setPageType(type);
        e.setCanRead(canRead);
        e.setCanCreate(0);
        e.setCanUpdate(0);
        e.setCanDelete(0);
        e.setWritePolicy("deny");
        return e;
    }

    private long seedPipeline(long kb) {
        WikiPipelineDefinitionEntity d = new WikiPipelineDefinitionEntity();
        d.setKbId(kb);
        d.setName("episode-to-pattern-" + SEQ.incrementAndGet());
        d.setOwnerAgentId(99L);
        d.setTriggerType("page_type_count");
        d.setTriggerConfigJson("{\"page_type\":\"episode\",\"threshold\":2}");
        d.setStepsJson("[{\"id\":\"s\",\"executor\":\"noop\"}]");
        d.setEnabled(1);
        d.setCreateTime(LocalDateTime.now());
        d.setUpdateTime(LocalDateTime.now());
        definitionMapper.insert(d);
        return d.getId();
    }
}
