package vip.mate.agent.binding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.binding.service.AgentBindingSkillRemovalListener;
import vip.mate.skill.event.SkillRemovedEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #127 regression — deleting a skill from the skill management page
 * left orphan rows in {@code mate_agent_skill}, so the agent edit modal kept
 * the old binding count and the user couldn't clear it. This listener drops
 * those rows in response to {@link SkillRemovedEvent}.
 */
class AgentBindingSkillRemovalListenerTest {

    @Test
    @DisplayName("event triggers a delete on mate_agent_skill scoped to the removed skill id")
    void removalDropsBindingRows() {
        AgentSkillBindingMapper mapper = mock(AgentSkillBindingMapper.class);
        when(mapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);

        AgentBindingSkillRemovalListener listener = new AgentBindingSkillRemovalListener(mapper);
        listener.onSkillRemoved(new SkillRemovedEvent(77L, "pdf"));

        verify(mapper, times(1)).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("null event or null skillId is a no-op — defensive guard")
    void nullEventDoesNothing() {
        AgentSkillBindingMapper mapper = mock(AgentSkillBindingMapper.class);
        AgentBindingSkillRemovalListener listener = new AgentBindingSkillRemovalListener(mapper);

        listener.onSkillRemoved(null);
        listener.onSkillRemoved(new SkillRemovedEvent(null, "dangling"));

        verify(mapper, never()).delete(any());
    }
}
