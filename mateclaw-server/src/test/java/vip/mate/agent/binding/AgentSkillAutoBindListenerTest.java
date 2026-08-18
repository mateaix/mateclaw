package vip.mate.agent.binding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.skill.event.SkillAuthoredEvent;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the auto-bind listener that makes a self-authored skill reachable
 * from the authoring agent's catalog.
 *
 * <p>The behaviour under test is entirely about which of the three binding
 * states justify writing a row — binding in the wrong state silently revokes
 * skills the agent already had, or overrides an explicit operator decision.
 */
class AgentSkillAutoBindListenerTest {

    private AgentBindingService bindingService;
    private AgentSkillAutoBindListener listener;

    @BeforeEach
    void setUp() {
        bindingService = mock(AgentBindingService.class);
        listener = new AgentSkillAutoBindListener(bindingService);
    }

    private SkillAuthoredEvent event() {
        return new SkillAuthoredEvent(99L, "spring-scaffold", 1L, "conv-1", 1L);
    }

    @Test
    @DisplayName("explicit allowlist → the new skill is bound so the agent can see it")
    void bindsWhenAgentUsesAllowlist() {
        when(bindingService.getBoundSkillIds(1L)).thenReturn(Set.of(7L, 8L));

        listener.onSkillAuthored(event());

        verify(bindingService, times(1)).bindSkill(1L, 99L);
    }

    @Test
    @DisplayName("no bindings (inherits every skill) → no row written")
    void skipsWhenAgentInheritsGlobalDefault() {
        // null means "no agent-level restriction". Writing a row here would
        // flip the agent into allowlist mode holding exactly this one skill,
        // revoking everything else it could previously reach.
        when(bindingService.getBoundSkillIds(1L)).thenReturn(null);

        listener.onSkillAuthored(event());

        verify(bindingService, never()).bindSkill(anyLong(), anyLong());
    }

    @Test
    @DisplayName("explicitly scoped to zero skills → operator intent is respected")
    void skipsWhenAgentScopedToNoSkills() {
        when(bindingService.getBoundSkillIds(1L)).thenReturn(Set.of());

        listener.onSkillAuthored(event());

        verify(bindingService, never()).bindSkill(anyLong(), anyLong());
    }

    @Test
    @DisplayName("skill already bound → no duplicate write")
    void skipsWhenAlreadyBound() {
        when(bindingService.getBoundSkillIds(1L)).thenReturn(Set.of(7L, 99L));

        listener.onSkillAuthored(event());

        verify(bindingService, never()).bindSkill(anyLong(), anyLong());
    }

    @Test
    @DisplayName("no agent origin → nothing to bind to")
    void skipsWhenAgentIdMissing() {
        listener.onSkillAuthored(new SkillAuthoredEvent(99L, "s", null, "conv-1", 1L));

        verify(bindingService, never()).getBoundSkillIds(any());
        verify(bindingService, never()).bindSkill(anyLong(), anyLong());
    }

    @Test
    @DisplayName("bind failure is swallowed — the skill itself is already persisted")
    void swallowsBindFailure() {
        when(bindingService.getBoundSkillIds(1L)).thenReturn(Set.of(7L));
        when(bindingService.bindSkill(eq(1L), eq(99L)))
                .thenThrow(new IllegalStateException("cross-workspace binding"));

        listener.onSkillAuthored(event());

        verify(bindingService, times(1)).bindSkill(1L, 99L);
    }
}
