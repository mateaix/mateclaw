package vip.mate.wiki.hotcache;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiHotCacheEntity;
import vip.mate.wiki.repository.WikiHotCacheMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiHotCacheServiceTest {

    private WikiHotCacheMapper mapper;
    private WikiHotCacheService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WikiHotCacheMapper.class);
        service = new WikiHotCacheService(mapper);
    }

    @Test
    @DisplayName("findByKb returns the row when one exists")
    void findByKb_returnsRow() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setKbId(7L);
        row.setContent("# Last Updated\nsomething");
        when(mapper.selectOne(any())).thenReturn(row);

        assertThat(service.findByKb(7L)).hasValueSatisfying(e -> {
            assertThat(e.getKbId()).isEqualTo(7L);
            assertThat(e.getContent()).contains("Last Updated");
        });
    }

    @Test
    @DisplayName("findByKb returns empty when no row")
    void findByKb_empty() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.findByKb(7L)).isEmpty();
    }

    @Test
    @DisplayName("findByKb short-circuits on null kbId")
    void findByKb_nullId() {
        assertThat(service.findByKb(null)).isEmpty();
        verify(mapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("getContentOrNull unwraps body")
    void getContentOrNull_present() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setKbId(7L);
        row.setContent("body");
        when(mapper.selectOne(any())).thenReturn(row);

        assertThat(service.getContentOrNull(7L)).isEqualTo("body");
    }

    @Test
    @DisplayName("getContentOrNull returns null when row missing")
    void getContentOrNull_missing() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.getContentOrNull(7L)).isNull();
    }

    @Test
    @DisplayName("softDelete delegates to mapper.deleteById (logical delete)")
    void softDelete_delegates() {
        service.softDelete(42L);
        verify(mapper).deleteById(42L);
    }

    @Test
    @DisplayName("softDelete short-circuits on null id")
    void softDelete_nullId() {
        service.softDelete(null);
        verify(mapper, never()).deleteById((java.io.Serializable) any());
    }

    @Test
    @DisplayName("HotCacheContent renders markdown with all sections")
    void content_rendersMarkdown() {
        HotCacheContent content = HotCacheContent.builder()
                .updatedAt(java.time.Instant.parse("2026-05-02T08:30:00Z"))
                .lastUpdatedSummary("ingested 3 papers on RedLock")
                .keyRecentFacts(java.util.List.of(
                        "RedLock has known safety issues under network partition",
                        "Internal Redis 7.4 release notes confirm scheduled deprecation in 8.0"))
                .recentChanges(java.util.List.of(
                        "Created: [[redlock-safety-analysis]]",
                        "Updated: [[distributed-locks]]"))
                .activeThreads(java.util.List.of(
                        "Open question: should we recommend ZooKeeper for new services?"))
                .build();

        String md = content.toMarkdown();

        assertThat(md).contains("type: meta");
        assertThat(md).contains("updated: 2026-05-02T08:30:00Z");
        assertThat(md).contains("## Last Updated\ningested 3 papers on RedLock");
        assertThat(md).contains("## Key Recent Facts\n- RedLock has known safety issues");
        assertThat(md).contains("## Recent Changes\n- Created: [[redlock-safety-analysis]]");
        assertThat(md).contains("## Active Threads\n- Open question: should we recommend ZooKeeper");
    }

    @Test
    @DisplayName("HotCacheContent renders (none) for empty sections")
    void content_emptySections() {
        HotCacheContent content = HotCacheContent.builder()
                .updatedAt(java.time.Instant.parse("2026-05-02T08:30:00Z"))
                .lastUpdatedSummary("")
                .build();

        String md = content.toMarkdown();

        assertThat(md).contains("## Last Updated\n(no recent activity)");
        assertThat(md).contains("## Key Recent Facts\n(none)");
        assertThat(md).contains("## Recent Changes\n(none)");
        assertThat(md).contains("## Active Threads\n(none)");
    }
}
