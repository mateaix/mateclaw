package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DuplicateKeyException;
import vip.mate.wiki.model.WikiImageCaptionCacheEntity;
import vip.mate.wiki.repository.WikiImageCaptionCacheMapper;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiImageCaptionCacheService}.
 */
class WikiImageCaptionCacheServiceTest {

    private static final String SAMPLE_SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private WikiImageCaptionCacheMapper mapper;
    private WikiImageCaptionCacheService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WikiImageCaptionCacheMapper.class);
        service = new WikiImageCaptionCacheService(mapper);
    }

    @Test
    @DisplayName("lookup returns empty for null or blank SHA")
    void lookup_blankInput() {
        assertThat(service.lookup(null)).isEmpty();
        assertThat(service.lookup("")).isEmpty();
        assertThat(service.lookup("   ")).isEmpty();

        verify(mapper, never()).selectOne(ArgumentMatchers.<Wrapper<WikiImageCaptionCacheEntity>>any());
    }

    @Test
    @DisplayName("lookup returns the row from DB and bumps hit_count on success")
    void lookup_hit_bumpsCounter() {
        WikiImageCaptionCacheEntity row = sampleRow();
        when(mapper.selectOne(ArgumentMatchers.<Wrapper<WikiImageCaptionCacheEntity>>any()))
                .thenReturn(row);

        Optional<WikiImageCaptionCacheEntity> result = service.lookup(SAMPLE_SHA);

        assertThat(result).isPresent();
        assertThat(result.get().getCaption()).isEqualTo(row.getCaption());
        verify(mapper, atLeastOnce()).bumpHitCount(eq(SAMPLE_SHA));
    }

    @Test
    @DisplayName("lookup returns empty on miss without attempting hit_count bump")
    void lookup_miss_noBump() {
        when(mapper.selectOne(ArgumentMatchers.<Wrapper<WikiImageCaptionCacheEntity>>any()))
                .thenReturn(null);

        Optional<WikiImageCaptionCacheEntity> result = service.lookup(SAMPLE_SHA);

        assertThat(result).isEmpty();
        verify(mapper, never()).bumpHitCount(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("lookup tolerates a hit_count bump failure (counter is best-effort)")
    void lookup_bumpFailureSwallowed() {
        WikiImageCaptionCacheEntity row = sampleRow();
        when(mapper.selectOne(ArgumentMatchers.<Wrapper<WikiImageCaptionCacheEntity>>any()))
                .thenReturn(row);
        when(mapper.bumpHitCount(eq(SAMPLE_SHA)))
                .thenThrow(new RuntimeException("DB hiccup on counter increment"));

        Optional<WikiImageCaptionCacheEntity> result = service.lookup(SAMPLE_SHA);

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("persist requires a non-null sha")
    void persist_rejectsBlankSha() {
        WikiImageCaptionCacheEntity row = sampleRow();
        row.setImageSha256(null);

        assertThatThrownBy(() -> service.persist(row))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.persist(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("persist inserts a fresh row")
    void persist_insertsRow() {
        WikiImageCaptionCacheEntity row = sampleRow();
        when(mapper.insert(row)).thenReturn(1);

        service.persist(row);

        verify(mapper).insert(row);
    }

    @Test
    @DisplayName("persist swallows DuplicateKeyException — race resolved by DB unique key")
    void persist_swallowsDuplicateKey() {
        WikiImageCaptionCacheEntity row = sampleRow();
        when(mapper.insert(row)).thenThrow(new DuplicateKeyException("uk_wicc_sha"));

        // Should not throw — earlier writer's value is acceptable.
        service.persist(row);

        verify(mapper).insert(row);
    }

    private WikiImageCaptionCacheEntity sampleRow() {
        WikiImageCaptionCacheEntity row = new WikiImageCaptionCacheEntity();
        row.setImageSha256(SAMPLE_SHA);
        row.setCaption("A pie chart with three slices labeled red, green, blue.");
        row.setVisibleText("red green blue");
        row.setMimeType("image/png");
        row.setCaptureModel("qwen-vl-max");
        row.setProviderId("dashscope-vision");
        row.setDurationMs(1500L);
        row.setHitCount(0L);
        row.setCapturedAt(LocalDateTime.now());
        row.setDeleted(0);
        return row;
    }
}
