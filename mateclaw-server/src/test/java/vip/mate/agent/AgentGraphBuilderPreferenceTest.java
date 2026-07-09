package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.llm.routing.ProviderModelRef;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the preferred-model chain planning used by
 * {@link AgentGraphBuilder#buildFallbackChain}: explicit (provider, model)
 * entries lead in declared order (same provider may repeat with different
 * models), then every non-preferred provider follows in the global order with
 * its default model. Exact (provider, model) duplicates and blank ids are
 * dropped; tail entries never repeat a provider already led by an explicit
 * entry.
 */
class AgentGraphBuilderPreferenceTest {

    private static ProviderModelRef ref(String providerId, Long modelId) {
        return new ProviderModelRef(providerId, modelId);
    }

    private static List<String> keys(List<ProviderModelRef> plan) {
        return plan.stream()
                .map(r -> r.providerId() + "/" + (r.modelId() == null ? "default" : r.modelId()))
                .toList();
    }

    @Test
    @DisplayName("No preferences: tail = global order, all default models")
    void noPreferences() {
        var out = AgentGraphBuilder.planFallbackOrder(
                List.of(), List.of("openai", "anthropic", "dashscope"));
        assertEquals(List.of("openai/default", "anthropic/default", "dashscope/default"), keys(out));
    }

    @Test
    @DisplayName("Single provider-default preference moves to front, tail drops it")
    void singleProviderDefaultFront() {
        var out = AgentGraphBuilder.planFallbackOrder(
                List.of(ref("dashscope", null)),
                List.of("openai", "anthropic", "dashscope"));
        assertEquals(List.of("dashscope/default", "openai/default", "anthropic/default"), keys(out));
    }

    @Test
    @DisplayName("Same provider repeated with different models — both kept, in order")
    void sameProviderMultipleModels() {
        var out = AgentGraphBuilder.planFallbackOrder(
                List.of(ref("openai", 1L), ref("openai", 2L), ref("anthropic", 3L)),
                List.of("openai", "anthropic", "dashscope"));
        // explicit head in order, then only the un-named provider (dashscope) trails
        assertEquals(
                List.of("openai/1", "openai/2", "anthropic/3", "dashscope/default"),
                keys(out));
    }

    @Test
    @DisplayName("Exact (provider, model) duplicate is dropped")
    void exactDuplicateDropped() {
        var out = AgentGraphBuilder.planFallbackOrder(
                List.of(ref("openai", 1L), ref("openai", 1L)),
                List.of("openai", "anthropic"));
        assertEquals(List.of("openai/1", "anthropic/default"), keys(out));
    }

    @Test
    @DisplayName("Provider pinned by id is not re-added as a default tail entry")
    void pinnedProviderExcludedFromTail() {
        var out = AgentGraphBuilder.planFallbackOrder(
                List.of(ref("openai", 1L)),
                List.of("openai", "anthropic"));
        // openai already led explicitly → no extra openai/default in the tail
        assertEquals(List.of("openai/1", "anthropic/default"), keys(out));
    }

    @Test
    @DisplayName("Blank / null provider ids are ignored")
    void blankIdsIgnored() {
        var out = AgentGraphBuilder.planFallbackOrder(
                List.of(ref("", 1L), ref(null, 2L), ref("openai", 3L)),
                List.of("openai", "anthropic"));
        assertEquals(List.of("openai/3", "anthropic/default"), keys(out));
    }

    @Test
    @DisplayName("Preference for a provider absent from the global pool is still honoured")
    void preferenceForUnknownProvider() {
        var out = AgentGraphBuilder.planFallbackOrder(
                List.of(ref("ghost", 9L)),
                List.of("openai", "anthropic"));
        // ghost leads (pool gating happens later in buildFallbackChain), tail follows
        assertEquals(List.of("ghost/9", "openai/default", "anthropic/default"), keys(out));
    }
}
