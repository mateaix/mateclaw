package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiPageServiceTest {

    @Test
    void manualUpdateRefreshesUpdateTimeBeforePersisting() {
        WikiPageMapper mapper = mock(WikiPageMapper.class);
        WikiPageEntity page = new WikiPageEntity();
        page.setId(99L);
        page.setKbId(7L);
        page.setSlug("page");
        page.setContent("old");
        page.setSummary("old summary");
        page.setVersion(1);
        page.setLastUpdatedBy("ai");
        LocalDateTime oldUpdateTime = LocalDateTime.now().minusDays(1);
        page.setUpdateTime(oldUpdateTime);
        when(mapper.selectOne(any())).thenReturn(page);
        when(mapper.updateById(any(WikiPageEntity.class))).thenReturn(1);

        new WikiPageService(mapper, new ObjectMapper())
                .updatePageManually(7L, "page", "new body", null);

        assertTrue(page.getUpdateTime().isAfter(oldUpdateTime));
        verify(mapper).updateById(page);
    }
}
