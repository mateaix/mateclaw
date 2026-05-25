package vip.mate.wiki.hotcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HotCacheRebuildPromptBuilderTest {

    private HotCacheRebuildPromptBuilder builder;

    @BeforeEach
    void setUp() {
        PromptLoader.clearCache();
        HotCacheProperties props = new HotCacheProperties();
        builder = new HotCacheRebuildPromptBuilder(props);
    }

    private static WikiPageEntity page(String slug, String title) {
        WikiPageEntity p = new WikiPageEntity();
        p.setSlug(slug);
        p.setTitle(title);
        return p;
    }

    @Test
    @DisplayName("system prompt loads + reads as the rebuilder role document")
    void systemPromptLoads() {
        String system = builder.buildSystem();
        assertThat(system).contains("hot cache rebuilder");
        assertThat(system).contains("## Last Updated");
        assertThat(system).contains("## Key Recent Facts");
        assertThat(system).contains("## Recent Changes");
        assertThat(system).contains("## Active Threads");
    }

    @Test
    @DisplayName("user prompt substitutes all placeholders with provided inputs")
    void userPromptSubstitutes() {
        String user = builder.buildUser(
                "previous body content",
                "## 2026-05-02 ingest\n- 18:30 — uploaded paper",
                List.of(page("redlock", "RedLock"), page("paxos", "Paxos")),
                List.of(page("distributed-locks", "Distributed Locks")));

        assertThat(user).contains("previous body content");
        assertThat(user).contains("18:30 — uploaded paper");
        assertThat(user).contains("- [[redlock]] RedLock");
        assertThat(user).contains("- [[paxos]] Paxos");
        assertThat(user).contains("- [[distributed-locks]] Distributed Locks");
        // ISO timestamp injected — not asserting exact value, just shape
        assertThat(user).matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*");
        // No leftover placeholder tokens
        assertThat(user).doesNotContain("{previous_content}");
        assertThat(user).doesNotContain("{log_excerpt}");
        assertThat(user).doesNotContain("{recent_creates}");
        assertThat(user).doesNotContain("{recent_updates}");
        assertThat(user).doesNotContain("{iso_timestamp}");
        assertThat(user).doesNotContain("{recent_window}");
    }

    @Test
    @DisplayName("blank or null sections render as (none)")
    void blankSections() {
        String user = builder.buildUser(null, "", List.of(), List.of());

        // Each "(none)" appears once per missing section; we just check the
        // marker is present rather than counting.
        assertThat(user).contains("(none)");
        // Every placeholder still resolved.
        assertThat(user).doesNotContain("{");
    }

    @Test
    @DisplayName("oversized previous content is abbreviated to the configured cap")
    void abbreviatesPreviousContent() {
        HotCacheProperties tightProps = new HotCacheProperties();
        tightProps.setPreviousContentCap(50);
        HotCacheRebuildPromptBuilder tight = new HotCacheRebuildPromptBuilder(tightProps);

        String huge = "x".repeat(500);
        String user = tight.buildUser(huge, null, List.of(), List.of());

        assertThat(user).contains("…");
        // Substring "xxxx…" — at least 49 x's then ellipsis (cap=50 → 49 x + …)
        assertThat(user).contains("x".repeat(49) + "…");
        assertThat(user).doesNotContain("x".repeat(60));
    }

    @Test
    @DisplayName("oversized log excerpt is abbreviated to the configured cap")
    void abbreviatesLogExcerpt() {
        HotCacheProperties tightProps = new HotCacheProperties();
        tightProps.setLogExcerptCap(40);
        HotCacheRebuildPromptBuilder tight = new HotCacheRebuildPromptBuilder(tightProps);

        String log = "y".repeat(500);
        String user = tight.buildUser(null, log, List.of(), List.of());

        assertThat(user).contains("…");
        assertThat(user).doesNotContain("y".repeat(60));
    }

    @Test
    @DisplayName("missing slug or title falls back gracefully without NPE")
    void missingPageFields() {
        WikiPageEntity slugless = new WikiPageEntity();
        slugless.setTitle("title-only");

        WikiPageEntity titleless = new WikiPageEntity();
        titleless.setSlug("slug-only");

        String user = builder.buildUser(null, null, List.of(slugless, titleless), List.of());

        assertThat(user).contains("title-only");
        assertThat(user).contains("slug-only");
    }
}
