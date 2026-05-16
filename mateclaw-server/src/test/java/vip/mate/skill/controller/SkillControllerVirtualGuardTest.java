package vip.mate.skill.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.model.SkillEntity;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Mutation paths refuse virtual MCP/ACP skill ids upfront so the user
 * gets a clear redirect to the connection page instead of the previous
 * "技能不存在" 500 surfacing from a doomed mate_skill lookup.
 */
class SkillControllerVirtualGuardTest {

    private final SkillController controller = new SkillController(
            null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    @DisplayName("update on a virtual MCP skill id is rejected before hitting the service")
    void updateRejectsVirtualMcpId() {
        long virtualId = McpSkillBridge.VIRTUAL_ID_BASE + 42L;
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.update(virtualId, new SkillEntity(), null));
        assertTrue(ex.getMessage().contains("MCP/ACP"),
                "expected redirect-to-connection-page hint, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("update on a virtual ACP skill id is rejected before hitting the service")
    void updateRejectsVirtualAcpId() {
        long virtualAcpId = AcpSkillBridge.VIRTUAL_ID_BASE + 7L;
        // Sanity guard against the test's own arithmetic — any drift in
        // bridge layout should fail the test loudly here, not silently
        // pass elsewhere.
        assertTrue(AcpSkillBridge.isVirtualAcpSkillId(virtualAcpId),
                "test fixture id is not in ACP virtual range; ACP base layout changed?");
        assertThrows(MateClawException.class,
                () -> controller.update(virtualAcpId, new SkillEntity(), null));
    }

    @Test
    @DisplayName("delete / toggle / rescan all reject virtual ids the same way")
    void mutationFamilyAllGuarded() {
        long virtualId = McpSkillBridge.VIRTUAL_ID_BASE + 42L;
        assertThrows(MateClawException.class, () -> controller.delete(virtualId, null));
        assertThrows(MateClawException.class, () -> controller.toggle(virtualId, true, null));
        assertThrows(MateClawException.class, () -> controller.rescan(virtualId, null));
    }

    @Test
    @DisplayName("real skill ids fall through to the service (no false-positive guard)")
    void realIdNotGuarded() {
        // A Snowflake-shaped id below VIRTUAL_ID_BASE — should pass the
        // guard. The downstream service call will fail because we're
        // passing nulls, but the failure must be from the service layer,
        // not the guard.
        SkillController real = new SkillController(
                mock(vip.mate.skill.service.SkillService.class),
                null, null, null, null, null, null, null, null, null, null, null, null);
        long snowflakeId = 1_900_000_001_000_000_902L;
        // updateSkill on a mocked SkillService returns null without throwing,
        // which is fine — we just need to confirm the guard didn't fire.
        // A virtual-id call would have thrown MateClawException before
        // reaching the service.
        try {
            real.update(snowflakeId, new SkillEntity(), null);
        } catch (MateClawException e) {
            // The guard message contains "MCP/ACP"; any other MateClawException
            // (e.g. from the service layer) is acceptable.
            org.junit.jupiter.api.Assertions.assertFalse(e.getMessage().contains("MCP/ACP"),
                    "real id incorrectly treated as virtual: " + e.getMessage());
        } catch (Exception ignored) {
            // Service-layer failures are out of scope for this test.
        }
    }
}
