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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
    @DisplayName("relative agent override that escapes the workspace root via ../ falls back to workspace basePath")
    void relativeAgentOverrideEscapingWorkspaceFallsBackToWorkspace() {
        stubConversation("c5b", 7L, 99L);
        Path wsBase = tempDir.resolve("ws-root");
        when(workspaceService.getById(7L)).thenReturn(workspace(7L, wsBase.toString()));
        // Relative override climbs out of the workspace root — resolveAgentBasePath
        // rejects it; the resolver falls back to the workspace basePath.
        when(agentService.getAgent(99L)).thenReturn(agent(99L, "../../escape", 7L));

        ChatUploadLocationResolver r = resolver(tempDir);
        Path root = r.resolveUploadRoot("c5b");

        assertThat(root).isEqualTo(wsBase.toAbsolutePath().normalize()
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

    // ==================== conversationId path safety (issue #507) ====================

    @Test
    @DisplayName("sanitizeSegment: colon (and other unsafe chars) → underscore; safe ids unchanged")
    void sanitizeSegmentReplacesUnsafeChars() {
        // IM-channel id with the ':' that breaks Windows paths.
        assertThat(ChatUploadLocationResolver.sanitizeSegment("wecom:XuZhanFu"))
                .isEqualTo("wecom_XuZhanFu");
        // Separator-laden id is fully flattened to a single safe segment.
        assertThat(ChatUploadLocationResolver.sanitizeSegment("a/b\\c:d*e?"))
                .isEqualTo("a_b_c_d_e_");
        // Already-safe ids (web / webchat / numeric) are a no-op.
        assertThat(ChatUploadLocationResolver.sanitizeSegment("2055137662148763649"))
                .isEqualTo("2055137662148763649");
        assertThat(ChatUploadLocationResolver.sanitizeSegment("conv-abc_1.2"))
                .isEqualTo("conv-abc_1.2");
        assertThat(ChatUploadLocationResolver.sanitizeSegment(null)).isEmpty();
    }

    @Test
    @DisplayName("resolveConversationDir: colon id lands under the sanitized segment (no InvalidPathException)")
    void resolveConversationDirUsesSanitizedSegment() {
        stubConversation("wecom:XuZhanFu", null, null);
        when(workspaceService.getById(anyLong())).thenReturn(workspace(1L, null));

        ChatUploadLocationResolver r = resolver(tempDir);
        Path dir = r.resolveConversationDir("wecom:XuZhanFu");

        assertThat(dir).isEqualTo(tempDir.toAbsolutePath().normalize().resolve("wecom_XuZhanFu"));
    }

    @Test
    @DisplayName("candidate conversation dirs: sanitized first, then raw id for backward compat")
    void candidateConversationDirsIncludeSanitizedAndRaw() {
        stubConversation("wecom:XuZhanFu", null, null);
        when(workspaceService.getById(anyLong())).thenReturn(workspace(1L, null));

        ChatUploadLocationResolver r = resolver(tempDir);
        List<Path> dirs = r.resolveCandidateConversationDirs("wecom:XuZhanFu");
        Path base = tempDir.toAbsolutePath().normalize();

        int sanitizedIdx = dirs.indexOf(base.resolve("wecom_XuZhanFu"));
        assertThat(sanitizedIdx).isGreaterThanOrEqualTo(0);
        // On a POSIX filesystem the raw ':' dir is a legal (legacy) candidate,
        // ordered after the sanitized one.
        boolean posix = !System.getProperty("os.name").toLowerCase().contains("win");
        if (posix) {
            int rawIdx = dirs.indexOf(base.resolve("wecom:XuZhanFu"));
            assertThat(rawIdx).isGreaterThan(sanitizedIdx);
        }
    }

    @Test
    @DisplayName("candidate conversation dirs: safe id yields a single dir (no duplicate raw)")
    void candidateConversationDirsNoDuplicateForSafeId() {
        stubConversation("plainconv", null, null);
        when(workspaceService.getById(anyLong())).thenReturn(workspace(1L, null));

        ChatUploadLocationResolver r = resolver(tempDir);
        List<Path> dirs = r.resolveCandidateConversationDirs("plainconv");

        assertThat(dirs).containsExactly(tempDir.toAbsolutePath().normalize().resolve("plainconv"));
    }

    // ==================== date folders ====================

    private ChatUploadLocationResolver resolver(Path defaultDir, boolean dateFolders) {
        ChatUploadProperties props = new ChatUploadProperties();
        props.setBaseDir(defaultDir.toAbsolutePath().toString());
        props.setDateFolders(dateFolders);
        return new ChatUploadLocationResolver(conversationMapper, workspaceService, props, agentService);
    }

    @Test
    @DisplayName("resolveWriteDir: date folders on → {convDir}/{yyyy-MM-dd}")
    void writeDirAppendsDateSegmentWhenEnabled() {
        stubConversation("c-date", null, null);
        when(workspaceService.getById(anyLong())).thenReturn(workspace(1L, null));

        ChatUploadLocationResolver r = resolver(tempDir, true);
        Path dir = r.resolveWriteDir("c-date");

        assertThat(dir).isEqualTo(tempDir.toAbsolutePath().normalize()
                .resolve("c-date")
                .resolve(LocalDate.now().toString()));
    }

    @Test
    @DisplayName("resolveWriteDir: date folders off → flat conversation dir")
    void writeDirIsFlatWhenDisabled() {
        stubConversation("c-flat", null, null);
        when(workspaceService.getById(anyLong())).thenReturn(workspace(1L, null));

        ChatUploadLocationResolver r = resolver(tempDir, false);
        Path dir = r.resolveWriteDir("c-flat");

        assertThat(dir).isEqualTo(tempDir.toAbsolutePath().normalize().resolve("c-flat"));
    }

    @Test
    @DisplayName("dateScanDirs: flat dir first, then date subdirs newest-first; non-date subdirs ignored")
    void dateScanDirsOrderedNewestFirst() throws Exception {
        Path convDir = tempDir.resolve("c-scan");
        Files.createDirectories(convDir.resolve("2026-07-25"));
        Files.createDirectories(convDir.resolve("2026-07-26"));
        Files.createDirectories(convDir.resolve("preview"));

        List<Path> dirs = ChatUploadLocationResolver.dateScanDirs(convDir);

        assertThat(dirs).containsExactly(
                convDir,
                convDir.resolve("2026-07-26"),
                convDir.resolve("2026-07-25"));
    }

    @Test
    @DisplayName("findInConversationDir: resolves flat legacy files and date-subdir files")
    void findInConversationDirProbesBothLayouts() throws Exception {
        Path convDir = tempDir.resolve("c-find");
        Files.createDirectories(convDir.resolve("2026-07-26"));
        Files.writeString(convDir.resolve("flat.txt"), "legacy");
        Files.writeString(convDir.resolve("2026-07-26").resolve("dated.txt"), "new");

        assertThat(ChatUploadLocationResolver.findInConversationDir(convDir, "flat.txt"))
                .isEqualTo(convDir.resolve("flat.txt"));
        assertThat(ChatUploadLocationResolver.findInConversationDir(convDir, "dated.txt"))
                .isEqualTo(convDir.resolve("2026-07-26").resolve("dated.txt"));
        assertThat(ChatUploadLocationResolver.findInConversationDir(convDir, "missing.txt"))
                .isNull();
    }

    @Test
    @DisplayName("findInConversationDir: traversal escaping the conversation dir is rejected")
    void findInConversationDirRejectsTraversal() throws Exception {
        Path convDir = tempDir.resolve("c-guard");
        Files.createDirectories(convDir);
        Files.writeString(tempDir.resolve("outside.txt"), "secret");

        assertThat(ChatUploadLocationResolver.findInConversationDir(convDir, "../outside.txt"))
                .isNull();
    }

    @Test
    @DisplayName("findInConversationDir: a date-subdir probe cannot climb back into the conversation root")
    void findInConversationDirRejectsClimbOutOfDateDir() throws Exception {
        Path convDir = tempDir.resolve("c-climb");
        Files.createDirectories(convDir.resolve("2026-07-26"));
        Files.writeString(convDir.resolve("flat.txt"), "legacy");

        assertThat(ChatUploadLocationResolver.findInConversationDir(convDir, "2026-07-26/../flat.txt"))
                .isNull();
    }

    @Test
    @DisplayName("findInConversationDir: rooted or multi-segment stored names are rejected outright")
    void findInConversationDirRejectsNonBareNames() throws Exception {
        Path convDir = tempDir.resolve("c-bare");
        Files.createDirectories(convDir.resolve("2026-07-26"));
        Files.writeString(convDir.resolve("flat.txt"), "legacy");

        // Rooted names matter on Windows, where Path.resolve drops the base for
        // a rooted argument; rejecting them keeps the guard platform-agnostic.
        assertThat(ChatUploadLocationResolver.findInConversationDir(
                convDir, tempDir.toAbsolutePath() + "/flat.txt")).isNull();
        assertThat(ChatUploadLocationResolver.findInConversationDir(convDir, "2026-07-26/flat.txt"))
                .isNull();
        assertThat(ChatUploadLocationResolver.findInConversationDir(convDir, "..")).isNull();
        // The bare name still resolves.
        assertThat(ChatUploadLocationResolver.findInConversationDir(convDir, "flat.txt"))
                .isEqualTo(convDir.resolve("flat.txt"));
    }

    @Test
    @DisplayName("resolveExistingFile: end-to-end lookup across candidate dirs and layouts")
    void resolveExistingFileFindsDatedFile() throws Exception {
        stubConversation("c-e2e", null, null);
        when(workspaceService.getById(anyLong())).thenReturn(workspace(1L, null));

        ChatUploadLocationResolver r = resolver(tempDir, true);
        Path writeDir = r.resolveWriteDir("c-e2e");
        Files.createDirectories(writeDir);
        Files.writeString(writeDir.resolve("1777_a.png"), "img");

        assertThat(r.resolveExistingFile("c-e2e", "1777_a.png"))
                .isEqualTo(writeDir.resolve("1777_a.png"));
        assertThat(r.resolveExistingFile("c-e2e", "nope.png")).isNull();
    }
}
