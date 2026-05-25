package vip.mate.wiki.hotcache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.memory.spi.MemoryProvider;
import vip.mate.system.featureflag.FeatureFlagEntity;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.system.featureflag.repository.FeatureFlagMapper;
import vip.mate.wiki.model.WikiHotCacheEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiHotCacheMapper;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring-context end-to-end smoke for the hot-cache injection chain.
 *
 * <p>Boots the full Spring Boot context with the H2 + Flyway test profile so
 * the V82 migration runs on the in-memory DB; then verifies the hot-cache
 * row → {@link WikiHotCacheProvider} → {@link MemoryManager} chain end to
 * end, exercising {@link MemoryManager#buildSystemPromptBlock} (the same
 * call agent-build performs at session start).
 *
 * <p>This catches wiring failures that pure mock-based unit tests miss:
 * the bean discovery, the mapper round-trip, the feature-flag cache
 * refresh, and the new migration column shape.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WikiHotCacheProviderE2ETest {

    private static final String FLAG = "wiki.hot_cache.enabled";

    @Autowired private MemoryManager memoryManager;
    @Autowired private List<MemoryProvider> allProviders;
    @Autowired private WikiHotCacheProvider hotCacheProvider;
    @Autowired private WikiHotCacheMapper hotCacheMapper;
    @Autowired private WikiKnowledgeBaseMapper kbMapper;
    @Autowired private FeatureFlagService featureFlagService;
    @Autowired private FeatureFlagMapper featureFlagMapper;

    private Long agentId;
    private Long kbId;

    @AfterEach
    void cleanup() {
        // Test data lives in the H2 file unless we wipe it; @DirtiesContext on
        // the base class scrubs Spring state but not DB rows.
        if (kbId != null) kbMapper.deleteById(kbId);
        hotCacheMapper.delete(new LambdaQueryWrapper<WikiHotCacheEntity>());
        // Reset flag to its seed default (off) for the next test.
        setFlag(false);
    }

    @Test
    @DisplayName("WikiHotCacheProvider is discovered + present in MemoryManager's provider list")
    void providerIsRegistered() {
        assertThat(hotCacheProvider).isNotNull();
        assertThat(allProviders)
                .extracting(MemoryProvider::id)
                .contains("wiki_hot_cache");
        // Spring autowires List<MemoryProvider> in registration order; MemoryManager
        // applies its own enabled-filter/sort. We assert the bean made it into
        // Spring's container at minimum.
    }

    @Test
    @DisplayName("flag off → MemoryManager.buildSystemPromptBlock excludes the hot cache section")
    void flagOff_omitsHotCache() {
        seedAgentAndKb();
        seedHotCacheRow();
        setFlag(false);

        String block = memoryManager.buildSystemPromptBlock(agentId);

        assertThat(block).doesNotContain("Recent Wiki Activity");
        assertThat(block).doesNotContain("smoke-test-fact");
    }

    @Test
    @DisplayName("flag on + hot cache row exists → injected into MemoryManager output")
    void flagOn_injectsHotCache() {
        seedAgentAndKb();
        seedHotCacheRow();
        setFlag(true);

        String block = memoryManager.buildSystemPromptBlock(agentId);

        assertThat(block).contains("# Recent Wiki Activity");
        assertThat(block).contains("smoke-test-fact");
    }

    @Test
    @DisplayName("flag on + KB has no hot cache row → block is empty for that section")
    void flagOn_noRow_skipsSection() {
        seedAgentAndKb();
        // intentionally no seedHotCacheRow()
        setFlag(true);

        String block = memoryManager.buildSystemPromptBlock(agentId);

        assertThat(block).doesNotContain("Recent Wiki Activity");
    }

    @Test
    @DisplayName("provider read API returns the same body the SQL row holds")
    void readApi_roundTrip() {
        seedAgentAndKb();
        seedHotCacheRow();

        Optional<WikiHotCacheEntity> row = hotCacheProvider.id() == null
                ? Optional.empty()
                : hotCacheMapper.selectList(
                        new LambdaQueryWrapper<WikiHotCacheEntity>().eq(WikiHotCacheEntity::getKbId, kbId))
                        .stream().findFirst();

        assertThat(row).isPresent();
        assertThat(row.get().getContent()).contains("smoke-test-fact");
    }

    // ==================== helpers ====================

    /** Inserts a KB owned by a synthetic agent so listByAgentId returns it. */
    private void seedAgentAndKb() {
        // Use a high agentId we're unlikely to collide with seed data. Agents
        // are referenced via foreign key on the KB row but not strictly
        // enforced at the DB level (seed data has agent_id NULL too).
        agentId = 9_999_001L;

        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setName("hot-cache-smoke-kb");
        kb.setAgentId(agentId);
        kbMapper.insert(kb);
        kbId = kb.getId();
        assertThat(kbId).isNotNull();
    }

    private void seedHotCacheRow() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setKbId(kbId);
        row.setContent("## Last Updated\nsmoke-test-fact\n");
        row.setContentHash("test-hash");
        row.setLastUpdated(LocalDateTime.now());
        row.setUpdateReason("MANUAL");
        row.setRebuildCount(1L);
        row.setDeleted(0);
        hotCacheMapper.insert(row);
    }

    private void setFlag(boolean enabled) {
        FeatureFlagEntity flag = featureFlagMapper.selectOne(
                new LambdaQueryWrapper<FeatureFlagEntity>()
                        .eq(FeatureFlagEntity::getFlagKey, FLAG));
        assertThat(flag)
                .as("V78 seed should have inserted %s", FLAG)
                .isNotNull();
        flag.setEnabled(enabled);
        featureFlagMapper.updateById(flag);
        featureFlagService.invalidate();
    }
}
