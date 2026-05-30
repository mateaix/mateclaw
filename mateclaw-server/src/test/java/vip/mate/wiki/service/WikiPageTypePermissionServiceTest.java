package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiAgentPageTypePermissionEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiAgentPageTypePermissionMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiPageTypePermissionService} precedence and default
 * policy resolution. Mapper and KB service are mocked so no Spring context or
 * DB is needed.
 */
class WikiPageTypePermissionServiceTest {

    private static final long AGENT = 1L;
    private static final long KB = 7L;

    private WikiPageTypePermissionService service(List<WikiAgentPageTypePermissionEntity> rows, String configJson) {
        WikiAgentPageTypePermissionMapper mapper = mock(WikiAgentPageTypePermissionMapper.class);
        when(mapper.selectList(any())).thenReturn(rows);
        WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(KB);
        kb.setConfigContent(configJson);
        when(kbService.getById(KB)).thenReturn(kb);
        return new WikiPageTypePermissionService(mapper, kbService, new ObjectMapper());
    }

    private WikiAgentPageTypePermissionEntity row(String type, int read, int create, int update,
                                                  int delete, String writePolicy) {
        WikiAgentPageTypePermissionEntity e = new WikiAgentPageTypePermissionEntity();
        e.setAgentId(AGENT);
        e.setKbId(KB);
        e.setPageType(type);
        e.setCanRead(read);
        e.setCanCreate(create);
        e.setCanUpdate(update);
        e.setCanDelete(delete);
        e.setWritePolicy(writePolicy);
        return e;
    }

    @Test
    void noRowsNoConfig_readsAllowed_writesAllowedOptIn() {
        WikiPageTypePermissionService s = service(List.of(), null);
        assertTrue(s.canRead(AGENT, KB, "concept"));
        assertEquals(WikiPageTypePermissionService.WriteDecision.ALLOW,
                s.resolveWrite(AGENT, KB, "concept", WikiPageTypePermissionService.WriteOp.CREATE));
    }

    @Test
    void noRows_denyAllConfig_readsDenied() {
        WikiPageTypePermissionService s = service(List.of(), "{\"defaultReadPolicy\":\"deny_all\"}");
        assertFalse(s.canRead(AGENT, KB, "concept"));
    }

    @Test
    void exactRowWinsOverWildcard_forRead() {
        // wildcard allows read, but the exact 'analysis' row forbids it
        List<WikiAgentPageTypePermissionEntity> rows = List.of(
                row("*", 1, 0, 0, 0, "deny"),
                row("analysis", 0, 0, 0, 0, "deny"));
        WikiPageTypePermissionService s = service(rows, null);
        assertFalse(s.canRead(AGENT, KB, "analysis"));   // exact row forbids
        assertTrue(s.canRead(AGENT, KB, "concept"));     // falls to wildcard allow
    }

    @Test
    void wildcardAppliesWhenNoExactMatch() {
        WikiPageTypePermissionService s = service(List.of(row("*", 0, 0, 0, 0, "deny")), null);
        assertFalse(s.canRead(AGENT, KB, "anything"));
    }

    @Test
    void readIsCaseInsensitiveOnPageType() {
        WikiPageTypePermissionService s = service(List.of(row("Episode", 0, 0, 0, 0, "deny")), null);
        assertFalse(s.canRead(AGENT, KB, "episode"));
    }

    @Test
    void writeResolution_perOperationFlagAndPolicy() {
        // create allowed but gated by approval; delete flag off → denied
        WikiPageTypePermissionService s = service(
                List.of(row("episode", 1, 1, 0, 0, "approval_required")), null);
        assertEquals(WikiPageTypePermissionService.WriteDecision.APPROVAL_REQUIRED,
                s.resolveWrite(AGENT, KB, "episode", WikiPageTypePermissionService.WriteOp.CREATE));
        assertEquals(WikiPageTypePermissionService.WriteDecision.DENY,
                s.resolveWrite(AGENT, KB, "episode", WikiPageTypePermissionService.WriteOp.DELETE));
    }

    @Test
    void writeAllowPolicyResolvesToAllow() {
        WikiPageTypePermissionService s = service(
                List.of(row("episode", 1, 1, 1, 1, "allow")), null);
        assertEquals(WikiPageTypePermissionService.WriteDecision.ALLOW,
                s.resolveWrite(AGENT, KB, "episode", WikiPageTypePermissionService.WriteOp.UPDATE));
    }

    @Test
    void rowsExistButTypeUncovered_writeIsFailSafeDeny() {
        // KB is gated (a row exists) but no row covers 'concept' and no wildcard
        WikiPageTypePermissionService s = service(
                List.of(row("episode", 1, 1, 1, 1, "allow")), null);
        assertEquals(WikiPageTypePermissionService.WriteDecision.DENY,
                s.resolveWrite(AGENT, KB, "concept", WikiPageTypePermissionService.WriteOp.CREATE));
    }

    @Test
    void rowsExistButTypeUncovered_readFallsToDefaultPolicy() {
        // a row exists for 'episode' only; 'concept' read falls to KB default (allow_all)
        WikiPageTypePermissionService s = service(
                List.of(row("episode", 0, 0, 0, 0, "deny")), null);
        assertTrue(s.canRead(AGENT, KB, "concept"));
    }

    @Test
    void nullAgent_isAllowAll() {
        WikiPageTypePermissionService s = service(List.of(), "{\"defaultReadPolicy\":\"deny_all\"}");
        assertTrue(s.canRead(null, KB, "concept"));
    }

    // ==================== CRUD ====================

    @Test
    void saveRow_insertsWhenAbsent_normalizesTypeAndPolicy() {
        WikiAgentPageTypePermissionMapper mapper = mock(WikiAgentPageTypePermissionMapper.class);
        when(mapper.selectOne(any())).thenReturn(null); // no existing row
        WikiPageTypePermissionService s = new WikiPageTypePermissionService(
                mapper, mock(WikiKnowledgeBaseService.class), new ObjectMapper());

        WikiAgentPageTypePermissionEntity in = row("Episode", 1, 1, 0, 0, "BOGUS");
        in.setId(999L); // must be cleared on insert
        WikiAgentPageTypePermissionEntity saved = s.saveRow(in);

        assertEquals("episode", saved.getPageType());           // lowercased
        assertEquals("approval_required", saved.getWritePolicy()); // unknown → safe default
        assertNull(saved.getId());                               // id cleared for insert
        verify(mapper).insert((WikiAgentPageTypePermissionEntity) saved);
        verify(mapper, never()).updateById((WikiAgentPageTypePermissionEntity) any());
    }

    @Test
    void saveRow_updatesInPlaceWhenExisting() {
        WikiAgentPageTypePermissionMapper mapper = mock(WikiAgentPageTypePermissionMapper.class);
        WikiAgentPageTypePermissionEntity existing = row("episode", 0, 0, 0, 0, "deny");
        existing.setId(42L);
        when(mapper.selectOne(any())).thenReturn(existing);
        WikiPageTypePermissionService s = new WikiPageTypePermissionService(
                mapper, mock(WikiKnowledgeBaseService.class), new ObjectMapper());

        WikiAgentPageTypePermissionEntity in = row("episode", 1, 1, 1, 1, "allow");
        WikiAgentPageTypePermissionEntity saved = s.saveRow(in);

        assertEquals(42L, saved.getId());        // adopts existing id
        verify(mapper).updateById((WikiAgentPageTypePermissionEntity) saved);
        verify(mapper, never()).insert((WikiAgentPageTypePermissionEntity) any());
    }

    @Test
    void saveRow_blankPageTypeBecomesWildcard() {
        WikiAgentPageTypePermissionMapper mapper = mock(WikiAgentPageTypePermissionMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        WikiPageTypePermissionService s = new WikiPageTypePermissionService(
                mapper, mock(WikiKnowledgeBaseService.class), new ObjectMapper());

        WikiAgentPageTypePermissionEntity in = row("  ", 1, 0, 0, 0, "allow");
        assertEquals(WikiPageTypePermissionService.WILDCARD, s.saveRow(in).getPageType());
    }

    @Test
    void deleteRow_returnsTrueWhenRowRemoved() {
        WikiAgentPageTypePermissionMapper mapper = mock(WikiAgentPageTypePermissionMapper.class);
        when(mapper.deleteById(5L)).thenReturn(1);
        WikiPageTypePermissionService s = new WikiPageTypePermissionService(
                mapper, mock(WikiKnowledgeBaseService.class), new ObjectMapper());
        assertTrue(s.deleteRow(5L));
        assertFalse(s.deleteRow(null));
    }
}
