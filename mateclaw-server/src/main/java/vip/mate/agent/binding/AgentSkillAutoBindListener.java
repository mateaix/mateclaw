package vip.mate.agent.binding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.skill.event.SkillAuthoredEvent;

import java.util.Set;

/**
 * Makes a self-authored skill reachable from the catalog of the agent that
 * authored it.
 *
 * <h2>Why this exists</h2>
 * An agent's visible skill catalog is filtered by
 * {@link AgentBindingService#getBoundSkillIds(Long)}. That method has a
 * three-state contract:
 *
 * <ul>
 *   <li>{@code null} — no binding rows: the agent inherits every globally
 *       enabled skill, so a newly created skill is visible automatically.</li>
 *   <li>{@code Set.of()} — the agent is explicitly scoped to zero skills
 *       (opt-out flag, or every binding row disabled).</li>
 *   <li>non-empty — an explicit allowlist; anything not in it is invisible.</li>
 * </ul>
 *
 * Without this listener, an agent in the third state can author a skill,
 * persist it, and then never see it again — the catalog renderer filters the
 * new row straight out. Self-improvement writes into a hole: the skill exists
 * in the registry but the agent that learned it cannot reach it on the next
 * turn.
 *
 * <h2>Binding policy</h2>
 * Bind only when the agent is already in explicit-allowlist mode
 * (non-null, non-empty). The other two states are deliberately left alone:
 *
 * <ul>
 *   <li>{@code null} — writing a row here would flip the agent from "inherit
 *       everything" into allowlist mode containing exactly one skill, which
 *       would silently revoke every other skill it had. Strictly worse than
 *       doing nothing.</li>
 *   <li>{@code Set.of()} — the operator asked for an agent with no skills.
 *       Binding would also clear the {@code skills_disabled} flag as a side
 *       effect of {@link AgentBindingService#bindSkill}, overriding an
 *       explicit human decision from a background code path.</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSkillAutoBindListener {

    private final AgentBindingService agentBindingService;

    @EventListener
    public void onSkillAuthored(SkillAuthoredEvent event) {
        if (event == null || event.agentId() == null || event.skillId() == null) {
            return;
        }
        Set<Long> bound;
        try {
            bound = agentBindingService.getBoundSkillIds(event.agentId());
        } catch (Exception e) {
            log.warn("[SkillAutoBind] Could not resolve bindings for agent={}: {}",
                    event.agentId(), e.getMessage());
            return;
        }
        // null = inherits every enabled skill; empty = explicitly scoped to
        // none. Neither state should be rewritten by a background author.
        if (bound == null || bound.isEmpty()) {
            return;
        }
        if (bound.contains(event.skillId())) {
            return;
        }
        try {
            agentBindingService.bindSkill(event.agentId(), event.skillId());
            log.info("[SkillAutoBind] Bound self-authored skill '{}' (id={}) to agent={}",
                    event.skillName(), event.skillId(), event.agentId());
        } catch (Exception e) {
            // A cross-workspace skill, a deleted agent, or a concurrent unbind
            // all land here. The skill itself is already persisted and remains
            // usable through the global catalog, so this stays a warning.
            log.warn("[SkillAutoBind] Failed to bind skill '{}' (id={}) to agent={}: {}",
                    event.skillName(), event.skillId(), event.agentId(), e.getMessage());
        }
    }
}
