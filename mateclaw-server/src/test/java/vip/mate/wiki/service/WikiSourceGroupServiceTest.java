package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiSourceGroupEntity;
import vip.mate.wiki.repository.WikiSourceGroupMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRUD + delete-with-reassign behavior for {@link WikiSourceGroupService}.
 */
class WikiSourceGroupServiceTest {

    private static final Long KB_ID = 1L;

    private WikiSourceGroupMapper groupMapper;
    private WikiKnowledgeBaseService kbService;
    private WikiSourcePathValidator pathValidator;
    private WikiRawMaterialService rawService;
    private WikiSourceGroupService service;

    @BeforeEach
    void setUp() {
        groupMapper = mock(WikiSourceGroupMapper.class);
        kbService = mock(WikiKnowledgeBaseService.class);
        pathValidator = mock(WikiSourcePathValidator.class);
        rawService = mock(WikiRawMaterialService.class);
        service = new WikiSourceGroupService(groupMapper, kbService, pathValidator, rawService);

        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(KB_ID);
        kb.setWorkspaceId(7L);
        when(kbService.getById(KB_ID)).thenReturn(kb);
    }

    @Test
    @DisplayName("create: validates the path and denormalizes workspaceId from the KB")
    void create_validatesPathAndDenormalizesWorkspace() {
        WikiSourceGroupEntity group = service.create(KB_ID, "docs", "/data/docs", null, null, null);

        verify(pathValidator).validatePatternBase("/data/docs");
        assertEquals(KB_ID, group.getKbId());
        assertEquals(7L, group.getWorkspaceId());
        assertEquals(1, group.getEnabled(), "null enabled defaults to on");
        verify(groupMapper).insert(group);
    }

    @Test
    @DisplayName("create: propagates path validation failure")
    void create_invalidPathThrows() {
        doThrow(new IllegalArgumentException("outside allowed roots"))
                .when(pathValidator).validatePatternBase(anyString());

        assertThrows(IllegalArgumentException.class,
                () -> service.create(KB_ID, "docs", "/etc", null, null, null));
        verify(groupMapper, never()).insert(any(WikiSourceGroupEntity.class));
    }

    @Test
    @DisplayName("create: rejects an invalid cron expression with a 400")
    void create_invalidCronExprThrows() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.create(KB_ID, "docs", "/data/docs", null, "not a cron", null));

        assertEquals(400, ex.getCode());
        verify(groupMapper, never()).insert(any(WikiSourceGroupEntity.class));
    }

    @Test
    @DisplayName("create: rejects a duplicate alias within the same KB")
    void create_duplicateAliasThrows() {
        when(groupMapper.selectCount(any())).thenReturn(1L);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.create(KB_ID, "docs", "/data/docs", null, null, null));

        assertEquals(400, ex.getCode());
        verify(groupMapper, never()).insert(any(WikiSourceGroupEntity.class));
    }

    @Test
    @DisplayName("update: only overwrites fields that are non-null in the request")
    void update_partialFieldsOnly() {
        WikiSourceGroupEntity existing = new WikiSourceGroupEntity();
        existing.setId(5L);
        existing.setKbId(KB_ID);
        existing.setAlias("old-alias");
        existing.setPath("/data/old");
        existing.setEnabled(1);

        WikiSourceGroupEntity updated = service.update(existing, null, null, null, null, false);

        assertEquals("old-alias", updated.getAlias(), "null alias leaves existing value untouched");
        assertEquals("/data/old", updated.getPath());
        assertEquals(0, updated.getEnabled(), "enabled=false is applied");
        verify(pathValidator, never()).validatePatternBase(anyString());
        verify(groupMapper).updateById(existing);
    }

    @Test
    @DisplayName("delete: reassignTo null clears groupId on member raws")
    void delete_withoutReassignClearsGroup() {
        service.delete(KB_ID, 5L, null);

        verify(rawService).reassignGroup(KB_ID, 5L, null);
        verify(groupMapper).deleteById(5L);
    }

    @Test
    @DisplayName("delete: reassignTo set re-points member raws to the target group")
    void delete_withReassignMovesGroup() {
        service.delete(KB_ID, 5L, 9L);

        verify(rawService).reassignGroup(KB_ID, 5L, 9L);
        verify(groupMapper).deleteById(5L);
    }

    @Test
    @DisplayName("delete: tombstones the alias before soft-deleting so it can be reused")
    void delete_tombstonesAliasBeforeSoftDelete() {
        WikiSourceGroupEntity existing = new WikiSourceGroupEntity();
        existing.setId(5L);
        existing.setKbId(KB_ID);
        existing.setAlias("docs");
        when(groupMapper.selectById(5L)).thenReturn(existing);

        service.delete(KB_ID, 5L, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WikiSourceGroupEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(groupMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("docs#del#5"),
                "alias should be rewritten to a tombstone so the original alias can be reused");
        verify(groupMapper).deleteById(5L);
    }

    @Test
    @DisplayName("countRawByKbId delegates to the raw-material aggregate query")
    void countRawByKbId_delegatesToRawService() {
        when(rawService.countRawByGroup(KB_ID)).thenReturn(Map.of(5L, 3L));

        Map<Long, Long> counts = service.countRawByKbId(KB_ID);

        assertEquals(3L, counts.get(5L));
        verify(rawService, times(1)).countRawByGroup(KB_ID);
    }
}
