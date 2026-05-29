package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiKnowledgeBaseService#resolvePrimaryKb(Long)}.
 *
 * <p>{@code listByAgentId} returns both the agent's own KBs and shared
 * (agent-less) KBs, ordered by {@code update_time} descending. A naive
 * {@code get(0)} pick therefore hands back whichever KB was touched most
 * recently — which can be an unrelated shared KB. {@code resolvePrimaryKb}
 * must still return the KB actually bound to the agent.
 */
class WikiKnowledgeBaseServiceTest {

    private final WikiKnowledgeBaseMapper kbMapper = mock(WikiKnowledgeBaseMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final WikiKnowledgeBaseService service = new WikiKnowledgeBaseService(
            kbMapper, null, null, null, null, null, agentMapper);

    private static WikiKnowledgeBaseEntity kb(long id, Long agentId) {
        return kb(id, agentId, null);
    }

    private static WikiKnowledgeBaseEntity kb(long id, Long agentId, String name) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setId(id);
        entity.setAgentId(agentId);
        entity.setName(name);
        return entity;
    }

    private static WikiKnowledgeBaseEntity kb(long id, Long agentId, Long workspaceId, String name) {
        WikiKnowledgeBaseEntity entity = kb(id, agentId, name);
        entity.setWorkspaceId(workspaceId);
        return entity;
    }

    private static AgentEntity agent(long id, Long workspaceId, Long primaryKbId) {
        AgentEntity entity = new AgentEntity();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        entity.setPrimaryKbId(primaryKbId);
        return entity;
    }

    @Test
    @DisplayName("prefers the agent's bound KB even when a shared KB was updated more recently")
    void prefersBoundKbOverNewerSharedKb() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L, 100L));
        // listByAgentId order is update_time DESC: two shared KBs precede the bound one.
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null, 1L, "Shared"),
                kb(800L, 8L, 1L, "Legacy Other"),
                kb(100L, null, 1L, "Primary")));

        assertThat(service.resolvePrimaryKb(7L)).isNotNull();
        assertThat(service.resolvePrimaryKb(7L).getId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("falls back to the most recent shared KB when the agent has no bound KB")
    void fallsBackToSharedKbWhenNoneBound() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L, null));
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, 8L, 1L, "Most Recent"),
                kb(800L, null, 1L, "Older")));

        assertThat(service.resolvePrimaryKb(7L).getId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("two agents can share the same primary KB")
    void twoAgentsCanSharePrimaryKb() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L, 100L));
        when(agentMapper.selectById(8L)).thenReturn(agent(8L, 1L, 100L));
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(100L, null, 1L, "Shared Primary"),
                kb(900L, null, 1L, "Fallback")));

        assertThat(service.resolvePrimaryKb(7L).getId()).isEqualTo(100L);
        assertThat(service.resolvePrimaryKb(8L).getId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("primary KB pointing outside the agent workspace falls back")
    void primaryKbOutsideWorkspaceFallsBack() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L, 200L));
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null, 1L, "Workspace Fallback"),
                kb(200L, null, 2L, "Wrong Workspace")));

        assertThat(service.resolvePrimaryKb(7L).getId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("returns null when the agent can reach no knowledge base")
    void returnsNullWhenNoKb() {
        when(kbMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.resolvePrimaryKb(7L)).isNull();
    }

    // ==================== findByName ====================
    //
    // The wiki tools added a kbName parameter so the LLM can target a
    // non-primary KB. findByName is the resolution layer behind that
    // parameter — it must restrict the match to KBs visible to the agent
    // and refuse to silently fall through to the primary on a miss, so a
    // bad pick surfaces as a clear "use wiki_list_kbs" hint instead of
    // routing to the wrong KB.

    @Test
    @DisplayName("findByName matches by exact name within the agent's visible set")
    void findByNameMatchesVisibleKb() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null, "Shared Docs"),
                kb(100L, 7L, "Agent Personal KB")));

        WikiKnowledgeBaseEntity hit = service.findByName(7L, "Agent Personal KB");
        assertThat(hit).isNotNull();
        assertThat(hit.getId()).isEqualTo(100L);

        WikiKnowledgeBaseEntity sharedHit = service.findByName(7L, "Shared Docs");
        assertThat(sharedHit).isNotNull();
        assertThat(sharedHit.getId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("findByName returns null when name does not match any visible KB")
    void findByNameMissReturnsNull() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null, "Shared Docs"),
                kb(100L, 7L, "Agent Personal KB")));

        assertThat(service.findByName(7L, "Nonexistent KB")).isNull();
    }

    @Test
    @DisplayName("findByName is case-sensitive — LLM must copy the name verbatim")
    void findByNameIsCaseSensitive() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(100L, 7L, "Agent Personal KB")));

        assertThat(service.findByName(7L, "agent personal kb")).isNull();
        assertThat(service.findByName(7L, "Agent Personal KB")).isNotNull();
    }

    @Test
    @DisplayName("findByName returns null for blank / null kbName")
    void findByNameBlankReturnsNull() {
        assertThat(service.findByName(7L, null)).isNull();
        assertThat(service.findByName(7L, "")).isNull();
        assertThat(service.findByName(7L, "   ")).isNull();
    }

    // ==================== findByName ambiguity + findAllByName + findVisibleById ====================
    //
    // mate_wiki_knowledge_base has no unique constraint on name (one DB row
    // per workspace + (name nullable + duplicates allowed) by design), so
    // a non-blank kbName can match more than one visible KB. The single-
    // result findByName must not silently pick "the first one" in that
    // case — callers route through findAllByName + an ambiguous-error
    // surface so the LLM is forced to disambiguate by kbId.

    @Test
    @DisplayName("findByName returns null when more than one visible KB shares the name")
    void findByNameAmbiguousReturnsNull() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(100L, 7L, "Docs"),
                kb(900L, null, "Docs")));

        assertThat(service.findByName(7L, "Docs"))
                .as("ambiguous matches collapse to null — caller must use findAllByName")
                .isNull();
    }

    @Test
    @DisplayName("findAllByName returns every visible KB sharing the name")
    void findAllByNameReturnsAllMatches() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(100L, 7L, "Docs"),
                kb(900L, null, "Docs"),
                kb(800L, null, "Other")));

        List<WikiKnowledgeBaseEntity> hits = service.findAllByName(7L, "Docs");
        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(WikiKnowledgeBaseEntity::getId).containsExactly(100L, 900L);
    }

    @Test
    @DisplayName("findAllByName returns empty for blank kbName")
    void findAllByNameBlankReturnsEmpty() {
        assertThat(service.findAllByName(7L, null)).isEmpty();
        assertThat(service.findAllByName(7L, "  ")).isEmpty();
    }

    @Test
    @DisplayName("findVisibleById returns the KB only when it is in the agent's visibility set")
    void findVisibleByIdGate() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L, null));
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(100L, 7L, "Bound KB"),
                kb(900L, null, "Shared KB"),
                kb(800L, 8L, "Other Agent Primary KB")));

        // Visible: returned.
        assertThat(service.findVisibleById(7L, 100L)).isNotNull();
        assertThat(service.findVisibleById(7L, 900L)).isNotNull();
        assertThat(service.findVisibleById(7L, 800L)).isNotNull();

        // Not in visibility set: deliberate fail-closed gate so an LLM
        // can't pivot to an arbitrary KB by guessing an id.
        assertThat(service.findVisibleById(7L, 99999L)).isNull();
        assertThat(service.findVisibleById(7L, null)).isNull();
    }
}
