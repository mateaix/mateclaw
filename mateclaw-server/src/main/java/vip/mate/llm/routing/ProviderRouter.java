package vip.mate.llm.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelCapabilityService.Modality;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Capability-aware provider routing.
 *
 * <p>Given an agent's bound skills, aggregates the {@code requires-model}
 * capabilities they declare and uses that to:
 * <ul>
 *   <li>{@link #diagnosePrimary} — WARN when the chosen primary model is
 *       missing a capability the bound skills require;</li>
 *   <li>{@link #reorderForCapabilities} — lift providers that satisfy the
 *       required modalities to the head of the fallback chain;</li>
 *   <li>{@link #selectPrimary} — pick a primary model that satisfies the
 *       required modalities, falling back to the global default.</li>
 * </ul>
 *
 * <p>Binding data is read through {@link AgentBindingResolver}, an
 * abstraction declared in this package so the routing layer never depends
 * on the agent layer directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderRouter {

    private final SkillRuntimeService skillRuntimeService;
    private final AgentBindingResolver bindingService;
    private final ModelCapabilityService capabilityService;
    private final ModelConfigService modelConfigService;

    /**
     * Compute the union of capability requirements declared by the
     * skills bound to {@code agentId}. Returns an empty set when no
     * bindings exist or no skill declares {@code requires-model}.
     */
    public Set<String> aggregateModelNeeds(Long agentId) {
        if (agentId == null || skillRuntimeService == null) return Set.of();
        Set<Long> boundSkillIds = bindingService.getBoundSkillIds(agentId);
        if (boundSkillIds == null || boundSkillIds.isEmpty()) return Set.of();
        List<ResolvedSkill> all = skillRuntimeService.resolveAllSkillsStatus();
        Set<String> needs = new LinkedHashSet<>();
        for (ResolvedSkill r : all) {
            if (r == null || r.getId() == null) continue;
            if (!boundSkillIds.contains(r.getId())) continue;
            SkillManifest m = r.getManifest();
            if (m == null) continue;
            List<String> declared = m.getRequiresModel();
            if (declared == null || declared.isEmpty()) continue;
            needs.addAll(declared);
        }
        return needs;
    }

    /**
     * Diagnostic check: does the chosen primary model satisfy every
     * skill-declared capability? Logs a single WARN per gap.
     *
     * <p>Intentionally never throws — this is observability, not policy.
     */
    public void diagnosePrimary(Long agentId, ModelConfigEntity primary) {
        if (primary == null || agentId == null) return;
        Set<String> needs = aggregateModelNeeds(agentId);
        if (needs.isEmpty()) return;
        EnumSet<Modality> resolved = capabilityService.resolve(
                primary.getModelName(), primary.getModalities());
        for (String need : needs) {
            Modality required = mapToModality(need);
            if (required == null) continue; // capability we can't translate (e.g. function_calling) — skip
            if (!resolved.contains(required)) {
                log.warn("[ProviderRouter] agent={} primary={}/{} missing capability '{}' " +
                                "required by bound skills (resolved: {})",
                        agentId, primary.getProvider(), primary.getModelName(),
                        need, resolved);
            }
        }
    }

    /**
     * Translate a manifest {@code requires-model} token to a
     * {@link Modality}. Tokens that don't map (e.g. {@code function_calling},
     * {@code long_context_100k}) return null — the caller skips diagnostics
     * for them rather than emit a noisy warning we can't act on yet.
     */
    private Modality mapToModality(String need) {
        if (need == null) return null;
        String n = need.trim().toLowerCase();
        return switch (n) {
            case "vision", "image", "vl" -> Modality.VISION;
            case "video" -> Modality.VIDEO;
            case "audio", "speech" -> Modality.AUDIO;
            default -> null;
        };
    }

    /**
     * Convenience for tests / health checks: return the current model's
     * capability resolution as a structured summary (provider/model →
     * modalities).
     */
    public String summarize(Long agentId) {
        try {
            ModelConfigEntity primary = modelConfigService.getDefaultModel();
            EnumSet<Modality> resolved = capabilityService.resolve(
                    primary.getModelName(), primary.getModalities());
            Set<String> needs = aggregateModelNeeds(agentId);
            return String.format("primary=%s/%s modalities=%s needs=%s",
                    primary.getProvider(), primary.getModelName(), resolved, needs);
        } catch (Exception e) {
            return "ProviderRouter summary unavailable: " + e.getMessage();
        }
    }

    // ==================== chain reorder ====================

    /**
     * Re-rank an already preference-ordered provider list so providers
     * that satisfy the agent's bound-skill {@code requires-model} union
     * float to the head. Stable order otherwise — providers that don't
     * satisfy keep their existing relative order.
     *
     * <p>Called when building the fallback chain, after the user-preferences
     * reorder. Only acts when bound skills actually declared
     * {@code requires-model}; otherwise returns the input untouched.
     */
    public List<ModelProviderEntity> reorderForCapabilities(Long agentId,
                                                             List<ModelProviderEntity> ordered) {
        if (ordered == null || ordered.isEmpty()) return ordered;
        Set<String> needs = aggregateModelNeeds(agentId);
        if (needs.isEmpty()) return ordered;
        Set<Modality> requiredModalities = needs.stream()
                .map(this::mapToModality)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(
                        () -> EnumSet.noneOf(Modality.class)));
        if (requiredModalities.isEmpty()) {
            // No modality-mapped need (e.g. only function_calling
            // declared) — let the existing order win.
            return ordered;
        }

        List<ModelProviderEntity> satisfying = new ArrayList<>();
        List<ModelProviderEntity> rest = new ArrayList<>();
        for (ModelProviderEntity p : ordered) {
            if (providerSatisfies(p, requiredModalities)) satisfying.add(p);
            else rest.add(p);
        }
        if (satisfying.isEmpty() || satisfying.size() == ordered.size()) {
            // Either nothing matches (let preference order ride) or
            // everything matches (no work to do).
            return ordered;
        }
        log.info("[ProviderRouter] agent={} reorder: {} provider(s) lifted for needs={}",
                agentId, satisfying.size(), requiredModalities);
        List<ModelProviderEntity> reordered = new ArrayList<>(ordered.size());
        reordered.addAll(satisfying);
        reordered.addAll(rest);
        return reordered;
    }

    /**
     * Pick a primary {@link ModelConfigEntity} that satisfies as many
     * required modalities as possible. Falls back to the global default
     * when nothing better is configured.
     *
     * <p>Logic: try each preferred provider in turn; for each, ask
     * {@link ModelProviderService#getDefaultModelByProvider} for its
     * default chat model and check capability resolution. First match
     * wins. If nothing matches, return the global default unchanged.
     */
    public ModelConfigEntity selectPrimary(Long agentId, ModelConfigEntity globalDefault) {
        if (agentId == null) return globalDefault;
        Set<String> needs = aggregateModelNeeds(agentId);
        if (needs.isEmpty()) return globalDefault;
        Set<Modality> requiredModalities = needs.stream()
                .map(this::mapToModality)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(
                        () -> EnumSet.noneOf(Modality.class)));
        if (requiredModalities.isEmpty()) return globalDefault;

        // Already satisfies? Skip the search.
        if (globalDefault != null) {
            EnumSet<Modality> resolved = capabilityService.resolve(
                    globalDefault.getModelName(), globalDefault.getModalities());
            if (resolved.containsAll(requiredModalities)) return globalDefault;
        }

        List<String> preferred = bindingService.getPreferredProviderIds(agentId);
        for (String providerId : preferred) {
            ModelConfigEntity candidate = pickProviderDefault(providerId);
            if (candidate == null) continue;
            EnumSet<Modality> resolved = capabilityService.resolve(
                    candidate.getModelName(), candidate.getModalities());
            if (resolved.containsAll(requiredModalities)) {
                log.info("[ProviderRouter] agent={} switched primary to {}/{} for needs={}",
                        agentId, candidate.getProvider(), candidate.getModelName(),
                        requiredModalities);
                return candidate;
            }
        }
        // No preferred provider satisfied; keep the diagnostic warning
        // path on the original default so the user sees the gap in logs.
        return globalDefault;
    }

    private ModelConfigEntity pickProviderDefault(String providerId) {
        if (providerId == null || providerId.isBlank()) return null;
        try {
            return modelConfigService.getDefaultModelByProvider(providerId);
        } catch (Exception e) {
            // getDefaultModelByProvider can return null or throw when
            // the provider has no enabled chat model; treat both as
            // "no candidate from this provider".
            return null;
        }
    }

    private boolean providerSatisfies(ModelProviderEntity provider, Set<Modality> needs) {
        ModelConfigEntity def = pickProviderDefault(provider.getProviderId());
        if (def == null) return false;
        return capabilityService.resolve(def.getModelName(), def.getModalities()).containsAll(needs);
    }
}
