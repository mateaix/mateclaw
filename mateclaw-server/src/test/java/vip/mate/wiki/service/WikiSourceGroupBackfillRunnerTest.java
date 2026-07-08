package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiSourceGroupEntity;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Idempotency and line-splitting behavior of {@link WikiSourceGroupBackfillRunner}:
 * a KB's legacy multi-line sourceDirectory is split into one group per line on
 * first run, and re-running (or a KB that already has groups) never duplicates.
 */
class WikiSourceGroupBackfillRunnerTest {

    private WikiKnowledgeBaseService kbService;
    private WikiSourceGroupService groupService;
    private WikiSourceGroupBackfillRunner runner;

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        groupService = mock(WikiSourceGroupService.class);
        runner = new WikiSourceGroupBackfillRunner(kbService, groupService);
    }

    private WikiKnowledgeBaseEntity kb(Long id, String sourceDirectory) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(id);
        kb.setSourceDirectory(sourceDirectory);
        return kb;
    }

    @Test
    @DisplayName("multi-line sourceDirectory is split into one group per line")
    void splitsMultiLineSourceDirectoryIntoGroups() {
        when(kbService.listAll()).thenReturn(List.of(kb(1L, "/data/docs\n/data/notes")));
        when(groupService.listByKbId(1L)).thenReturn(List.of());

        runner.run(null);

        verify(groupService).create(eq(1L), eq("/data/docs"), eq("/data/docs"),
                isNull(), eq("incremental"), isNull(), eq(true));
        verify(groupService).create(eq(1L), eq("/data/notes"), eq("/data/notes"),
                isNull(), eq("incremental"), isNull(), eq(true));
    }

    @Test
    @DisplayName("KB with no sourceDirectory is skipped")
    void skipsKbWithoutSourceDirectory() {
        when(kbService.listAll()).thenReturn(List.of(kb(1L, null), kb(2L, "  ")));

        runner.run(null);

        verifyNoInteractions(groupService);
    }

    @Test
    @DisplayName("KB that already has a source group is skipped entirely (idempotent)")
    void skipsKbThatAlreadyHasGroups() {
        when(kbService.listAll()).thenReturn(List.of(kb(1L, "/data/docs")));
        WikiSourceGroupEntity existing = new WikiSourceGroupEntity();
        existing.setId(9L);
        existing.setKbId(1L);
        when(groupService.listByKbId(1L)).thenReturn(List.of(existing));

        runner.run(null);

        verify(groupService, never()).create(eq(1L), anyString(), anyString(), isNull(), anyString(), isNull(), anyBoolean());
    }

    @Test
    @DisplayName("re-running after groups exist does not duplicate them")
    void reRunIsIdempotent() {
        when(kbService.listAll()).thenReturn(List.of(kb(1L, "/data/docs")));
        when(groupService.listByKbId(1L))
                .thenReturn(List.of())
                .thenReturn(List.of(new WikiSourceGroupEntity()));

        runner.run(null);
        runner.run(null);

        verify(groupService, times(1)).create(eq(1L), anyString(), anyString(), isNull(), eq("incremental"), isNull(), eq(true));
    }
}
