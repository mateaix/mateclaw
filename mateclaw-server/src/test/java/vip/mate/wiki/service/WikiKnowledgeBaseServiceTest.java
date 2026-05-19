package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    private final WikiKnowledgeBaseService service = new WikiKnowledgeBaseService(
            kbMapper, null, null, null, null, null);

    private static WikiKnowledgeBaseEntity kb(long id, Long agentId) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setId(id);
        entity.setAgentId(agentId);
        return entity;
    }

    @Test
    @DisplayName("prefers the agent's bound KB even when a shared KB was updated more recently")
    void prefersBoundKbOverNewerSharedKb() {
        // listByAgentId order is update_time DESC: two shared KBs precede the bound one.
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null),
                kb(800L, null),
                kb(100L, 7L)));

        assertThat(service.resolvePrimaryKb(7L)).isNotNull();
        assertThat(service.resolvePrimaryKb(7L).getId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("falls back to the most recent shared KB when the agent has no bound KB")
    void fallsBackToSharedKbWhenNoneBound() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null),
                kb(800L, null)));

        assertThat(service.resolvePrimaryKb(7L).getId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("returns null when the agent can reach no knowledge base")
    void returnsNullWhenNoKb() {
        when(kbMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.resolvePrimaryKb(7L)).isNull();
    }
}
