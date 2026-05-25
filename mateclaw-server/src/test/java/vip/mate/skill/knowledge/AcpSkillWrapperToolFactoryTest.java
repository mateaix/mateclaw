package vip.mate.skill.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.acp.service.AcpDelegationService;
import vip.mate.acp.service.AcpEndpointService;
import vip.mate.skill.manifest.SkillManifest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RFC-090 Phase 7b — locks in the wrapper factory contract:
 *
 *  <ol>
 *    <li>name shape is {@code acp_<endpointSlug>_<skillSlug>_prompt}</li>
 *    <li>missing endpoint → empty list (resolver downgrades skill to
 *        SETUP_NEEDED rather than register broken tools)</li>
 *    <li>resolveEndpointId hits {@link AcpEndpointService#findByName}
 *        and returns the row id when present</li>
 *    <li>callback delegates to {@link AcpDelegationService#prompt} and
 *        bakes in the manifest's {@code system_prefix}</li>
 *    <li>empty input → JSON error (caller can decide what to do)</li>
 *  </ol>
 */
class AcpSkillWrapperToolFactoryTest {

    private AcpEndpointService endpointService;
    private AcpDelegationService delegationService;
    private AcpSkillWrapperToolFactory factory;

    @BeforeEach
    void setUp() {
        endpointService = mock(AcpEndpointService.class);
        delegationService = mock(AcpDelegationService.class);
        factory = new AcpSkillWrapperToolFactory(
                endpointService, delegationService, new ObjectMapper());
    }

    @Test
    @DisplayName("wrapperNames returns the canonical acp_<endpoint>_<slug>_prompt shape")
    void wrapperNamesShape() {
        SkillManifest m = SkillManifest.builder()
                .name("Team-Codex Helper") // mixed case + dash
                .acp(SkillManifest.AcpBinding.builder().endpoint("codex").build())
                .build();
        List<String> names = factory.wrapperNames(m);
        assertEquals(1, names.size());
        assertEquals("acp_codex_team_codex_helper_prompt", names.get(0));
    }

    @Test
    @DisplayName("buildWrappers returns empty when no acp binding")
    void buildWrappersNoBinding() {
        SkillManifest m = SkillManifest.builder().name("foo").build();
        assertTrue(factory.buildWrappers(m).isEmpty());
    }

    @Test
    @DisplayName("resolveEndpointId hits findByName and returns id")
    void resolveEndpointIdLooksUpName() {
        AcpEndpointEntity ep = new AcpEndpointEntity();
        ep.setId(42L);
        when(endpointService.findByName("codex")).thenReturn(ep);
        assertEquals(42L, factory.resolveEndpointId("codex"));
        verify(endpointService).findByName("codex");
    }

    @Test
    @DisplayName("resolveEndpointId returns null for missing endpoint")
    void resolveEndpointIdMissing() {
        when(endpointService.findByName("ghost")).thenReturn(null);
        assertNull(factory.resolveEndpointId("ghost"));
    }

    @Test
    @DisplayName("callback delegates to AcpDelegationService and prepends system_prefix")
    void callbackDelegates() {
        SkillManifest m = SkillManifest.builder()
                .name("codex-helper")
                .acp(SkillManifest.AcpBinding.builder()
                        .endpoint("codex")
                        .systemPrefix("Be concise.")
                        .cwd("/tmp/proj")
                        .build())
                .build();
        when(delegationService.prompt(eq("codex"), any(String.class), eq("/tmp/proj")))
                .thenReturn("DONE");

        List<ToolCallback> wrappers = factory.buildWrappers(m);
        assertEquals(1, wrappers.size());
        String out = wrappers.get(0).call("{\"prompt\":\"hello\"}");
        assertTrue(out.contains("\"reply\""));
        assertTrue(out.contains("DONE"));

        // Composed prompt should carry system_prefix + blank line + user text.
        verify(delegationService).prompt(eq("codex"),
                argThat((String s) -> s.contains("Be concise.") && s.contains("hello")),
                eq("/tmp/proj"));
    }

    @Test
    @DisplayName("callback returns JSON error when prompt is empty")
    void callbackEmptyPromptError() {
        SkillManifest m = SkillManifest.builder()
                .name("codex-helper")
                .acp(SkillManifest.AcpBinding.builder().endpoint("codex").build())
                .build();
        List<ToolCallback> wrappers = factory.buildWrappers(m);
        String out = wrappers.get(0).call("{}");
        assertTrue(out.contains("\"error\""));
        verifyNoInteractions(delegationService);
    }
}
