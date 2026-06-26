package vip.mate.workspace.core.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.config.ChatUploadProperties;
import vip.mate.workspace.core.model.WorkspaceEntity;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChatUploadLocationResolver}'s resolution precedence
 * (agent override → workspace basePath → configurable default) and its
 * dual-lookup candidate ordering (workspace-scoped root first, then the
 * default fallback root).
 */
class ChatUploadLocationResolverTest {

    @TempDir
    Path tempDir;

    private ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private WorkspaceService workspaceService = mock(WorkspaceService.class);
    private AgentService agentService = mock(AgentService.class);

    private ChatUploadLocationResolver resolver(Path defaultDir) {
        ChatUploadProperties props = new ChatUploadProperties();
        props.setBaseDir(defaultDir.toAbsolutePath().toString());
        return new ChatUploadLocationResolver(conversationMapper, workspaceService, props, agentService);
    }

    private void stubConversation(String convId, Long workspaceId, Long agentId) {
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId(convId);
        conv.setWorkspaceId(workspaceId);
        conv.setAgentId(agentId);
        conv.setDeleted(0);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conv);
    }

    private WorkspaceEntity workspace(Long id, String basePath) {
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setId(id);
        ws.setBasePath(basePath);
        return ws;
    }

    private AgentEntity agent(Long id, String workspaceBasePath, Long workspaceId) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setWorkspaceBasePath(workspaceBasePath);
        a.setWorkspaceId(workspaceId);
        return a;
    }

    @Test
    @DisplayName("no agent and no workspace basePath → configurable default root")
    void resolvesToDefaultWhenNothingConfigured() {
        stubConversation("c1", 7L, null);
        when(workspaceService.getById(7L)).thenReturn(workspace(7L, null));

        ChatUploadLocationResolver r = resolver(tempDir);
        Path root = r.resolveUploadRoot("c1");

        // The default root IS the chat-uploads dir (no extra subdir appended),
        // so conversation dirs land directly under it: {defaultDir}/{convId}/.
        assertThat(root).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("workspace basePath set, no agent override → {basePath}/chat-uploads")
    void resolvesToWorkspaceBasePath() {
        stubConversation("c2", 7L, null);
        Path wsBase = tempDir.resolve("ws-root");
        when(workspaceService.getById(7L)).thenReturn(workspace(7L, wsBase.toString()));

        ChatUploadLocationResolver r = resolver(tempDir);
        Path root = r.resolveUploadRoot("c2");

        assertThat(root).isEqualTo(wsBase.toAbsolutePath().normalize()
                .resolve(ChatUploadLocationResolver.UPLOAD_SUBDIR));
    }

    @Test
    @DisplayName("agent override wins over workspace basePath")
    void agentOverrideWinsOverWorkspace() {
        stubConversation("c3", 7L, 99L);
        Path wsBase = tempDir.resolve("ws-root");
        when(workspaceService.getById(7L)).thenReturn(workspace(7L, wsBase.toString()));
        // Absolute override that sits inside the workspace root — allowed, and wins.
        Path agentOverride = wsBase.resolve("agent-override");
        when(agentService.getAgent(99L)).thenReturn(agent(99L, agentOverride.toString(), 7L));

        ChatUploadLocationResolver r = resolver(tempDir);
        Path root = r.resolveUploadRoot("c3");

        assertThat(root).isEqualTo(agentOverride.toAbsolutePath().normalize()
                .resolve(ChatUploadLocationResolver.UPLOAD_SUBDIR));
    }

    @Test
    @DisplayName("agent override that escapes the workspace root falls back to workspace basePath")
    void agentOverrideEscapingWorkspaceFallsBackToWorkspace() {
        stubConversation("c4", 7L, 99L);
        Path wsBase = tempDir.resolve("ws-root");
        when(workspaceService.getById(7L)).thenReturn(workspace(7L, wsBase.toString()));
        // Override points outside the workspace root — resolveAgentBasePath rejects it;
        // the resolver falls back to the workspace basePath.
        when(agentService.getAgent(99L)).thenReturn(agent(99L, "/etc", 7L));

        ChatUploadLocationResolver r = resolver(tempDir);
        Path root = r.resolveUploadRoot("c4");

        assertThat(root).isEqualTo(wsBase.toAbsolutePath().normalize()
                .resolve(ChatUploadLocationResolver.UPLOAD_SUBDIR));
    }

    @Test
    @DisplayName("relative agent override is resolved under the workspace basePath")
    void relativeAgentOverrideResolvedUnderWorkspace() {
        stubConversation("c5", 7L, 99L);
        Path wsBase = tempDir.resolve("ws-root");
        when(workspaceService.getById(7L)).thenReturn(workspace(7L, wsBase.toString()));
        when(agentService.getAgent(99L)).thenReturn(agent(99L, "subdir", 7L));

        ChatUploadLocationResolver r = resolver(tempDir);
        Path root = r.resolveUploadRoot("c5");

        assertThat(root).isEqualTo(wsBase.toAbsolutePath().normalize()
                .resolve("subdir")
                .resolve(ChatUploadLocationResolver.UPLOAD_SUBDIR));
    }

    @Test
    @DisplayName("candidate roots: workspace-scoped first, then default (dual-lookup order)")
    void candidateRootsOrderedScopedThenDefault() {
        stubConversation("c6", 7L, null);
        Path wsBase = tempDir.resolve("ws-root");
        when(workspaceService.getById(7L)).thenReturn(workspace(7L, wsBase.toString()));

        ChatUploadLocationResolver r = resolver(tempDir);
        List<Path> candidates = r.resolveCandidateUploadRoots("c6");

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0)).isEqualTo(wsBase.toAbsolutePath().normalize()
                .resolve(ChatUploadLocationResolver.UPLOAD_SUBDIR));
        assertThat(candidates.get(1)).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("candidate roots: only the default when nothing configured (no duplicate)")
    void candidateRootsOnlyDefaultWhenUnconfigured() {
        stubConversation("c7", 7L, null);
        when(workspaceService.getById(7L)).thenReturn(workspace(7L, null));

        ChatUploadLocationResolver r = resolver(tempDir);
        List<Path> candidates = r.resolveCandidateUploadRoots("c7");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0)).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("unknown conversation → falls back to default root (no NPE)")
    void unknownConversationFallsBackToDefault() {
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(workspaceService.getById(anyLong())).thenReturn(workspace(1L, null));

        ChatUploadLocationResolver r = resolver(tempDir);
        Path root = r.resolveUploadRoot("nonexistent");

        assertThat(root).isEqualTo(tempDir.toAbsolutePath().normalize());
    }
}
