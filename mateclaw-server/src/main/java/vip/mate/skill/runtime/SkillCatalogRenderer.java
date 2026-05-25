package vip.mate.skill.runtime;

import java.util.Set;

/**
 * Renders the agent-scoped skill catalog segment at runtime so its ordering can
 * react to skills loaded during the current graph run.
 * <p>
 * Built once per agent (capturing the agent's bound skills, effective tool
 * allowlist, model window and workspace), then invoked each turn by the
 * reasoning / step-execution nodes with the set of skills already loaded this
 * run. Loaded skills are pinned to the top of the catalog so a multi-iteration
 * loop stops re-loading something it already pulled into message history.
 */
@FunctionalInterface
public interface SkillCatalogRenderer {

    /**
     * Render the {@code ## Skills} catalog segment.
     *
     * @param loadedThisRun skill names loaded via {@code load_skill} so far in
     *                      this run; pinned to the top of the catalog. Never
     *                      {@code null} — pass an empty set when nothing loaded.
     * @return the catalog markdown, or an empty string when the agent has no
     *         visible skills.
     */
    String render(Set<String> loadedThisRun);
}
