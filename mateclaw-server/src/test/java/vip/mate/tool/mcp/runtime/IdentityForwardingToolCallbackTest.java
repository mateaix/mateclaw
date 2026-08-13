package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdentityForwardingToolCallback} — verifies it acts as a
 * type marker + identity provider for the {@code _meta} path (#459 follow-up:
 * identity moved from args to _meta). Identity resolution itself is tested in
 * {@link McpIdentityForwardServiceTest}.
 */
class IdentityForwardingToolCallbackTest {

    @Test
    @DisplayName("metaInjection returns the plaintext identity map for an authenticated user")
    void metaInjectionPlaintext() {
        McpIdentityForwardService svc = mock(McpIdentityForwardService.class);
        when(svc.resolveMeta(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("my-api")))
                .thenReturn(Map.of(McpIdentityForwardProperties.META_USER_KEY, "authenticated:42"));

        IdentityForwardingToolCallback cb = new IdentityForwardingToolCallback(
                mock(org.springframework.ai.tool.ToolCallback.class), svc, "my-api");

        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);
        ToolContext ctx = new ToolContext(Map.of(ChatOrigin.CTX_KEY, origin));

        Map<String, String> meta = cb.metaInjection(ctx);
        assertThat(meta).containsEntry(McpIdentityForwardProperties.META_USER_KEY, "authenticated:42");
    }

    @Test
    @DisplayName("metaInjection returns empty map when no identity (cron / system)")
    void metaInjectionEmptyForCron() {
        McpIdentityForwardService svc = mock(McpIdentityForwardService.class);
        when(svc.resolveMeta(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of());

        IdentityForwardingToolCallback cb = new IdentityForwardingToolCallback(
                mock(org.springframework.ai.tool.ToolCallback.class), svc, "my-api");

        ChatOrigin origin = ChatOrigin.cron("c1", 1L, null, 9L, null);
        ToolContext ctx = new ToolContext(Map.of(ChatOrigin.CTX_KEY, origin));

        assertThat(cb.metaInjection(ctx)).isEmpty();
    }

    @Test
    @DisplayName("call() forwards to delegate without modifying args (identity is in _meta now)")
    void callForwardsUnchanged() {
        org.springframework.ai.tool.ToolCallback delegate = mock(org.springframework.ai.tool.ToolCallback.class);
        when(delegate.call("{\"q\":\"hi\"}")).thenReturn("result");

        McpIdentityForwardService svc = mock(McpIdentityForwardService.class);
        IdentityForwardingToolCallback cb = new IdentityForwardingToolCallback(delegate, svc, "my-api");

        // call(String) — no args modification
        assertThat(cb.call("{\"q\":\"hi\"}")).isEqualTo("result");
        // Verify the args were NOT modified (identity no longer merged into args)
        org.mockito.Mockito.verify(delegate).call("{\"q\":\"hi\"}");
    }

    @Test
    @DisplayName("opt-in matching: by id or name, empty set never forwards")
    void optInMatching() {
        McpIdentityForwardProperties p = new McpIdentityForwardProperties();
        assertThat(p.forwardsTo(42L, "svc")).isFalse();           // empty set
        p.setServers(Set.of("svc"));
        assertThat(p.forwardsTo(42L, "svc")).isTrue();            // by name
        assertThat(p.forwardsTo(42L, "other")).isFalse();
        p.setServers(Set.of("42"));
        assertThat(p.forwardsTo(42L, "other")).isTrue();          // by id
    }
}
