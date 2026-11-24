package vip.mate.skill.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillControllerMcpWorkspaceTest {
    @Test void foreignVirtualSkillIsAbsentFromDetailAndRuntimeStatus() {
        var controller = mock(SkillController.class, CALLS_REAL_METHODS);
        var bridge = mock(McpSkillBridge.class);
        var runtime = mock(SkillRuntimeService.class);
        ReflectionTestUtils.setField(controller, "mcpSkillBridge", bridge);
        ReflectionTestUtils.setField(controller, "skillRuntimeService", runtime);
        var foreign = new SkillEntity(); foreign.setId(McpSkillBridge.VIRTUAL_ID_BASE + 88L); foreign.setWorkspaceId(2L);
        when(bridge.listMcpDerivedSkillEntities()).thenReturn(List.of(foreign));
        assertNull(controller.get(foreign.getId(), 1L).getData());
        assertNotNull(controller.get(foreign.getId(), 2L).getData());
        var global = ResolvedSkill.builder().id(1L).name("global").workspaceId(null).build();
        var own = ResolvedSkill.builder().id(2L).name("own").workspaceId(1L).build();
        var other = ResolvedSkill.builder().id(3L).name("foreign").workspaceId(2L).build();
        when(runtime.resolveAllSkillsStatus()).thenReturn(List.of(global, own, other));
        assertEquals(List.of(global, own), controller.getRuntimeStatus(1L).getData());
        assertEquals(List.of(global, own), controller.getRuntimeStatus(null).getData());
        assertNull(controller.requirements(3L, 1L).getData());
    }
}
