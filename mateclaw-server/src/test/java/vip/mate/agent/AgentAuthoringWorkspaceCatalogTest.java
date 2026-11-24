package vip.mate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentAuthoringWorkspaceCatalogTest {
    @Test
    void catalogAndEmployeeBindingsUseTheOriginWorkspace() throws Exception {
        var agents = mock(AgentService.class);
        var bindings = mock(AgentBindingService.class);
        var skills = mock(SkillMapper.class);
        var tools = mock(AvailableToolService.class);
        var mapper = new ObjectMapper();
        var local = AvailableToolDTO.builder().name("local_tool").description("local_description").available(true).build();
        var foreign = AvailableToolDTO.builder().name("foreign_tool").description("foreign_secret").available(true).build();
        when(tools.listAvailable(42L)).thenReturn(List.of(local));
        when(tools.listAvailable()).thenReturn(List.of(local, foreign));
        when(agents.createAgent(any())).thenAnswer(invocation -> {
            AgentEntity agent = invocation.getArgument(0);
            agent.setId(99L);
            return agent;
        });
        var tool = new AgentAuthoringTool(agents, bindings, skills, tools, mapper);
        var context = ChatOrigin.web("conversation", "123", 42L, null).toToolContext();

        String catalog = tool.list_capability_catalog(context);
        assertTrue(catalog.contains("local_description"));
        assertFalse(catalog.contains("foreign_secret"));
        String created = tool.create_employee("helper", "description", "prompt", null, null,
                null, "[\"local_tool\",\"foreign_tool\"]", context);
        assertEquals(List.of("local_tool"), mapper.convertValue(mapper.readTree(created).get("toolsBound"), List.class));
        verify(bindings).setToolBindings(99L, List.of("local_tool"));
        verify(tools, times(2)).listAvailable(42L);
        verify(tools, never()).listAvailable();
    }
}
