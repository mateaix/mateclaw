package vip.mate.wiki.hotcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WikiHotCacheProviderTest {

    private WikiHotCacheService cacheService;
    private WikiKnowledgeBaseService kbService;
    private FeatureFlagService featureFlagService;
    private WikiHotCacheProvider provider;

    @BeforeEach
    void setUp() {
        cacheService = mock(WikiHotCacheService.class);
        kbService = mock(WikiKnowledgeBaseService.class);
        featureFlagService = mock(FeatureFlagService.class);
        provider = new WikiHotCacheProvider(cacheService, kbService, featureFlagService);

        when(featureFlagService.isEnabled("wiki.hot_cache.enabled")).thenReturn(true);
    }

    private static WikiKnowledgeBaseEntity kb(Long id, String name) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(id);
        kb.setName(name);
        return kb;
    }

    @Test
    @DisplayName("flag off → empty block")
    void flagOff() {
        when(featureFlagService.isEnabled("wiki.hot_cache.enabled")).thenReturn(false);
        assertThat(provider.systemPromptBlock(7L)).isEmpty();
    }

    @Test
    @DisplayName("null agentId → empty block")
    void nullAgent() {
        assertThat(provider.systemPromptBlock(null)).isEmpty();
    }

    @Test
    @DisplayName("agent has no KBs → empty block")
    void noKbs() {
        when(kbService.listByAgentId(7L)).thenReturn(List.of());
        assertThat(provider.systemPromptBlock(7L)).isEmpty();
    }

    @Test
    @DisplayName("KB present but no hot cache row → empty block")
    void kbWithoutCache() {
        when(kbService.listByAgentId(7L)).thenReturn(List.of(kb(100L, "Engineering")));
        when(cacheService.getContentOrNull(100L)).thenReturn(null);
        assertThat(provider.systemPromptBlock(7L)).isEmpty();
    }

    @Test
    @DisplayName("KB present with blank cache content → empty block")
    void kbWithBlankCache() {
        when(kbService.listByAgentId(7L)).thenReturn(List.of(kb(100L, "Engineering")));
        when(cacheService.getContentOrNull(100L)).thenReturn("   ");
        assertThat(provider.systemPromptBlock(7L)).isEmpty();
    }

    @Test
    @DisplayName("single KB with cache → header + body")
    void singleKb() {
        when(kbService.listByAgentId(7L)).thenReturn(List.of(kb(100L, "Engineering")));
        when(cacheService.getContentOrNull(100L)).thenReturn("## Last Updated\nfoo");

        String block = provider.systemPromptBlock(7L);

        assertThat(block).startsWith("# Recent Wiki Activity\n\n");
        assertThat(block).contains("## Last Updated\nfoo");
        // Single KB: no per-KB heading
        assertThat(block).doesNotContain("## Engineering");
    }

    @Test
    @DisplayName("two KBs with cache → header + first body + second KB heading + body")
    void twoKbs() {
        when(kbService.listByAgentId(7L)).thenReturn(List.of(
                kb(100L, "Engineering"), kb(200L, "Product")));
        when(cacheService.getContentOrNull(100L)).thenReturn("eng-body");
        when(cacheService.getContentOrNull(200L)).thenReturn("prod-body");

        String block = provider.systemPromptBlock(7L);

        assertThat(block).startsWith("# Recent Wiki Activity\n\n");
        assertThat(block).contains("eng-body");
        assertThat(block).contains("\n\n## Product\n\nprod-body");
    }

    @Test
    @DisplayName("three KBs → only first two contribute (prompt budget)")
    void capsAtTwo() {
        when(kbService.listByAgentId(7L)).thenReturn(List.of(
                kb(100L, "Engineering"), kb(200L, "Product"), kb(300L, "Marketing")));
        when(cacheService.getContentOrNull(100L)).thenReturn("eng-body");
        when(cacheService.getContentOrNull(200L)).thenReturn("prod-body");
        when(cacheService.getContentOrNull(300L)).thenReturn("mkt-body");

        String block = provider.systemPromptBlock(7L);

        assertThat(block).contains("eng-body");
        assertThat(block).contains("prod-body");
        assertThat(block).doesNotContain("mkt-body");
        assertThat(block).doesNotContain("## Marketing");
    }

    @Test
    @DisplayName("first KB has no cache → second KB still contributes as the leader")
    void skipsKbWithoutCache() {
        when(kbService.listByAgentId(7L)).thenReturn(List.of(
                kb(100L, "Engineering"), kb(200L, "Product")));
        when(cacheService.getContentOrNull(100L)).thenReturn(null);
        when(cacheService.getContentOrNull(200L)).thenReturn("prod-body");

        String block = provider.systemPromptBlock(7L);

        // Product is the only contributor → it gets the leading "Recent Wiki
        // Activity" header, not a per-KB sub-heading.
        assertThat(block).startsWith("# Recent Wiki Activity\n\n");
        assertThat(block).contains("prod-body");
        assertThat(block).doesNotContain("## Engineering");
        assertThat(block).doesNotContain("## Product");
    }

    @Test
    @DisplayName("kbService throws → empty block, no propagation")
    void kbServiceFails() {
        when(kbService.listByAgentId(eq(7L))).thenThrow(new RuntimeException("db down"));
        assertThat(provider.systemPromptBlock(7L)).isEmpty();
    }

    @Test
    @DisplayName("id and order are stable")
    void identity() {
        assertThat(provider.id()).isEqualTo("wiki_hot_cache");
        assertThat(provider.order()).isEqualTo(30);
    }
}
