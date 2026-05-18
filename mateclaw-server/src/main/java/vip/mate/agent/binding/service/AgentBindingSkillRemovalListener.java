package vip.mate.agent.binding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.skill.event.SkillRemovedEvent;

/**
 * Drops {@code mate_agent_skill} rows that pointed at a now-removed skill.
 *
 * <p>Without this listener, deleting a skill from the skill management page
 * leaves orphan binding rows behind:
 * <ul>
 *   <li>the agent edit modal still shows a non-zero badge from
 *       {@code GET /agents/{id}/skills},</li>
 *   <li>the picker list (sourced from {@code /skills} enabled set) no longer
 *       contains a checkbox for that id so the user can't uncheck it, and</li>
 *   <li>a subsequent {@code PUT /agents/{id}/skills} payload that still
 *       carries the orphan id is rejected by
 *       {@code AgentBindingService.setSkillBindings} with
 *       {@code err.skill.not_found}, leaving the user with no way to clear
 *       the stale binding.</li>
 * </ul>
 *
 * <p>The event is dispatched synchronously from {@code SkillService} after
 * the {@code mate_skill} row deletion, so the cleanup is part of the same
 * request and observable in the very next list call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentBindingSkillRemovalListener {

    private final AgentSkillBindingMapper skillBindingMapper;

    @EventListener
    public void onSkillRemoved(SkillRemovedEvent event) {
        if (event == null || event.skillId() == null) {
            return;
        }
        int dropped = skillBindingMapper.delete(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getSkillId, event.skillId()));
        if (dropped > 0) {
            log.info("Cleaned {} agent-skill binding row(s) for removed skill {} (id={})",
                    dropped, event.skillName(), event.skillId());
        }
    }
}
