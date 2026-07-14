package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;
import vip.mate.wiki.repository.WikiTransformationMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the starter-pack visibility bug: the 7 built-in transformation
 * templates were seeded with a hardcoded {@code workspace_id = 1}, so any other
 * workspace saw an empty Transformations list. V165 clears their workspace_id
 * (NULL = global) and the queries treat NULL as visible everywhere — this test
 * boots H2 with the real Flyway migrations (V108 seed + V165 fix) and asserts
 * the templates show up regardless of workspace, while workspace-scoped
 * templates stay isolated.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiTransformationStarterPackGlobalE2ETest {

    private static final Set<String> STARTER_PACK = Set.of(
            "contract-risk-extract", "meeting-action-items", "customer-profile",
            "competitor-update", "resume-structured-extract", "incident-postmortem", "paper-imrad");

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired
    private WikiTransformationService service;
    @Autowired
    private WikiKnowledgeBaseMapper kbMapper;
    @Autowired
    private WikiTransformationMapper transformationMapper;

    private long newKb(long workspaceId) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        long id = SEQ.incrementAndGet();
        kb.setId(id);
        kb.setName("kb-" + id);
        kb.setStatus("active");
        kb.setWorkspaceId(workspaceId);
        kb.setCreateTime(LocalDateTime.now());
        kb.setUpdateTime(LocalDateTime.now());
        kb.setDeleted(0);
        kbMapper.insert(kb);
        return id;
    }

    private void insertWorkspaceTemplate(long workspaceId, String name) {
        WikiTransformationEntity t = new WikiTransformationEntity();
        t.setId(SEQ.incrementAndGet());
        t.setKbId(null);                 // workspace-wide
        t.setWorkspaceId(workspaceId);   // but scoped to one workspace
        t.setName(name);
        t.setTitle(name);
        t.setPromptTemplate("do something");
        t.setEnabled(true);
        t.setCreateTime(LocalDateTime.now());
        t.setUpdateTime(LocalDateTime.now());
        t.setDeleted(0);
        transformationMapper.insert(t);
    }

    private Set<String> visibleNames(long kbId, long workspaceId) {
        return service.listForKb(kbId, workspaceId).stream()
                .map(WikiTransformationEntity::getName)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Starter pack is visible from a non-default workspace (the bug)")
    void starterPackVisibleFromOtherWorkspace() {
        long kb = newKb(999L);
        Set<String> names = visibleNames(kb, 999L);
        for (String expected : STARTER_PACK) {
            assertTrue(names.contains(expected),
                    "workspace 999 should see starter-pack template '" + expected + "', got: " + names);
        }
    }

    @Test
    @DisplayName("Starter pack still visible from workspace 1 (no regression)")
    void starterPackStillVisibleFromWorkspaceOne() {
        long kb = newKb(1L);
        assertTrue(visibleNames(kb, 1L).containsAll(STARTER_PACK));
    }

    @Test
    @DisplayName("A workspace-scoped template stays isolated; globals are seen by both")
    void workspaceScopedTemplateStaysIsolated() {
        String unique = "ws-only-" + SEQ.incrementAndGet();
        insertWorkspaceTemplate(777L, unique);

        long kb777 = newKb(777L);
        long kb888 = newKb(888L);

        assertTrue(visibleNames(kb777, 777L).contains(unique), "owner workspace should see its template");
        assertFalse(visibleNames(kb888, 888L).contains(unique), "other workspace must NOT see it");

        // Global starter pack reaches both workspaces.
        assertTrue(visibleNames(kb777, 777L).containsAll(STARTER_PACK));
        assertTrue(visibleNames(kb888, 888L).containsAll(STARTER_PACK));
    }

    @Test
    @DisplayName("listByWorkspace surfaces globals alongside the workspace's own")
    void listByWorkspaceIncludesGlobals() {
        List<WikiTransformationEntity> rows = service.listByWorkspace(424242L);
        Set<String> names = rows.stream().map(WikiTransformationEntity::getName).collect(Collectors.toSet());
        assertTrue(names.containsAll(STARTER_PACK),
                "listByWorkspace for an arbitrary workspace should still include the global starter pack");
    }

    @Test
    @DisplayName("Global starter-pack templates are read-only: update/delete rejected (regression for #456)")
    void globalTemplateIsReadOnly() {
        // Pick a real seeded global template (workspace_id NULL).
        WikiTransformationEntity before = service.listByWorkspace(1L).stream()
                .filter(t -> t.getWorkspaceId() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected at least one global template after V165"));
        long id = before.getId();
        String originalPrompt = before.getPromptTemplate();

        // update must be rejected, and the stored row must be untouched afterwards.
        WikiTransformationEntity patch = new WikiTransformationEntity();
        patch.setTitle("tampered title");
        MateClawException updateEx = assertThrows(MateClawException.class,
                () -> service.update(id, patch));
        assertEquals(403, updateEx.getCode());
        WikiTransformationEntity afterUpdate = service.getById(id);
        assertEquals(before.getTitle(), afterUpdate.getTitle(),
                "global template title must not change after a rejected update");
        assertEquals(originalPrompt, afterUpdate.getPromptTemplate());

        // delete must be rejected too; the row must still be present.
        MateClawException deleteEx = assertThrows(MateClawException.class,
                () -> service.delete(id));
        assertEquals(403, deleteEx.getCode());
        assertTrue(service.getById(id) != null, "global template must not be deleted");
    }

    @Test
    @DisplayName("findByName prefers a workspace-local template over a same-named global one")
    void findByNamePrefersWorkspaceLocalOverGlobal() {
        long ws = 555555L;
        long kb = newKb(ws);
        // A starter-pack name exists globally; create a workspace-wide clone with the same name.
        String sharedName = "contract-risk-extract";
        WikiTransformationEntity local = new WikiTransformationEntity();
        long localId = SEQ.incrementAndGet();
        local.setId(localId);
        local.setKbId(null);
        local.setWorkspaceId(ws);
        local.setName(sharedName);
        local.setTitle("local override");
        local.setPromptTemplate("local prompt");
        local.setEnabled(true);
        local.setCreateTime(LocalDateTime.now());
        local.setUpdateTime(LocalDateTime.now());
        local.setDeleted(0);
        transformationMapper.insert(local);

        Optional<WikiTransformationEntity> hit = service.findByName(kb, ws, sharedName);
        assertTrue(hit.isPresent());
        assertEquals(localId, hit.get().getId(),
                "findByName must return the workspace-local template, not the global starter pack");
    }
}
