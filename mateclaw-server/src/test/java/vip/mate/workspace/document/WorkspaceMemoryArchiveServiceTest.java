package vip.mate.workspace.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract for the agent workspace memory-snapshot export / import service.
 * <p>
 * The service is the gate between user-supplied ZIPs and the
 * {@code mate_workspace_file} table: each test pins one of the safety or
 * correctness invariants that prevents the import path from being abused —
 * cross-workspace writes, ZIP-bomb decompression, path traversal disguised
 * as a filename, the unchanged-content short-circuit, and the
 * preview / apply consistency that the UI relies on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceMemoryArchiveServiceTest {

    @Mock private WorkspaceFileService workspaceFileService;
    @Mock private AgentService agentService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkspaceMemoryArchiveService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceMemoryArchiveService(
                workspaceFileService, agentService, objectMapper);
    }

    // ---------- ownership ----------

    @Test
    @DisplayName("Cross-workspace agent → 403 MateClawException, no DB read of files")
    void crossWorkspaceForbidden() {
        AgentEntity agent = makeAgent(1L, 10L);  // belongs to workspace 10
        when(agentService.getAgent(1L)).thenReturn(agent);

        assertThatThrownBy(() -> service.export(1L, 20L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("does not belong");
        verify(workspaceFileService, never()).listFiles(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("Unknown agent → 404 MateClawException")
    void unknownAgentRejected() {
        when(agentService.getAgent(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.export(99L, 1L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Null workspaceId → 400 (controller forgot to forward the header)")
    void nullWorkspaceIdRejected() {
        // assertOwnership rejects null workspaceId before even touching agentService.
        assertThatThrownBy(() -> service.export(1L, null))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("workspaceId");
        verify(agentService, never()).getAgent(org.mockito.ArgumentMatchers.anyLong());
    }

    // ---------- export ----------

    @Test
    @DisplayName("Export bundles whitelisted files + a manifest, excludes others")
    void exportEmitsManifestAndWhitelistOnly() throws Exception {
        wireAgent(1L, 10L);
        when(workspaceFileService.listFiles(1L)).thenReturn(List.of(
                stubMeta("MEMORY.md"),
                stubMeta("memory/2026-05-10.md"),
                stubMeta("memory/2026-05-11.md"),
                // Outside the whitelist — must NOT make it into the archive.
                stubMeta("secrets.txt"),
                stubMeta("some-other.md")));
        when(workspaceFileService.getFile(eq(1L), eq("MEMORY.md")))
                .thenReturn(stubFile("MEMORY.md", "fact body"));
        when(workspaceFileService.getFile(eq(1L), eq("memory/2026-05-10.md")))
                .thenReturn(stubFile("memory/2026-05-10.md", "day 10"));
        when(workspaceFileService.getFile(eq(1L), eq("memory/2026-05-11.md")))
                .thenReturn(stubFile("memory/2026-05-11.md", "day 11"));

        byte[] bundle = service.export(1L, 10L);

        Map<String, byte[]> entries = readZip(bundle);
        assertThat(entries).containsKeys(
                WorkspaceMemoryArchiveService.MANIFEST_NAME,
                "MEMORY.md",
                "memory/2026-05-10.md",
                "memory/2026-05-11.md");
        assertThat(entries).doesNotContainKeys("secrets.txt", "some-other.md");

        // Manifest carries provenance.
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) objectMapper.readValue(
                entries.get(WorkspaceMemoryArchiveService.MANIFEST_NAME), Map.class);
        assertThat(manifest).containsEntry("version", WorkspaceMemoryArchiveService.BUNDLE_VERSION);
        assertThat(manifest).containsEntry("agentId", 1);  // Jackson reads Long → Integer when fits
        assertThat(manifest).containsKey("exportedAt");
    }

    // ---------- preview ----------

    @Test
    @DisplayName("Preview classifies create / update / skip correctly without writing")
    void previewClassifiesEntries() throws Exception {
        wireAgent(1L, 10L);
        // Existing files: MEMORY.md (will UPDATE — content changes),
        //                 PROFILE.md (will SKIP — content identical).
        when(workspaceFileService.getFile(1L, "MEMORY.md"))
                .thenReturn(stubFile("MEMORY.md", "old memory"));
        when(workspaceFileService.getFile(1L, "PROFILE.md"))
                .thenReturn(stubFile("PROFILE.md", "same persona"));
        // memory/2026-05-12.md doesn't exist → will CREATE.
        when(workspaceFileService.getFile(1L, "memory/2026-05-12.md"))
                .thenReturn(null);

        byte[] zip = makeZip(Map.of(
                "MEMORY.md", "NEW memory content",
                "PROFILE.md", "same persona",   // unchanged — should land in skip
                "memory/2026-05-12.md", "day 12 body",
                "not-allowed.bin", "binary blob"));

        WorkspaceMemoryArchiveService.ImportPreview preview =
                service.previewImport(1L, 10L, zip);

        assertThat(preview.willCreate()).containsExactlyInAnyOrder("memory/2026-05-12.md");
        assertThat(preview.willUpdate())
                .extracting(WorkspaceMemoryArchiveService.FileDiff::filename)
                .containsExactlyInAnyOrder("MEMORY.md");
        assertThat(preview.willSkip())
                .extracting(WorkspaceMemoryArchiveService.SkipEntry::filename,
                        WorkspaceMemoryArchiveService.SkipEntry::reason)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("PROFILE.md", "unchanged"),
                        org.assertj.core.groups.Tuple.tuple("not-allowed.bin", "not in whitelist"));

        // Critical: preview must NEVER call saveFile.
        verify(workspaceFileService, never()).saveFile(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Daily filename matching the digit shape but not a real date is rejected")
    void impossibleCalendarDateRejected() throws Exception {
        wireAgent(1L, 10L);
        when(workspaceFileService.getFile(1L, "memory/2026-05-12.md"))
                .thenReturn(null);

        byte[] zip = makeZip(Map.of(
                "memory/2026-05-12.md", "real date → create",
                "memory/2026-13-99.md", "month 13 / day 99 → reject",
                "memory/2026-02-30.md", "feb 30 does not exist → reject"));

        WorkspaceMemoryArchiveService.ImportPreview preview =
                service.previewImport(1L, 10L, zip);

        assertThat(preview.willCreate()).containsExactlyInAnyOrder("memory/2026-05-12.md");
        assertThat(preview.willSkip())
                .extracting(WorkspaceMemoryArchiveService.SkipEntry::filename,
                        WorkspaceMemoryArchiveService.SkipEntry::reason)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("memory/2026-13-99.md", "not in whitelist"),
                        org.assertj.core.groups.Tuple.tuple("memory/2026-02-30.md", "not in whitelist"));

        verify(workspaceFileService, never()).saveFile(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Preview surfaces old vs new hash + size for updated files")
    void previewExposesDiffMetadata() throws Exception {
        wireAgent(1L, 10L);
        when(workspaceFileService.getFile(1L, "MEMORY.md"))
                .thenReturn(stubFile("MEMORY.md", "old"));
        byte[] zip = makeZip(Map.of("MEMORY.md", "much-longer-new-content"));

        WorkspaceMemoryArchiveService.ImportPreview preview =
                service.previewImport(1L, 10L, zip);

        assertThat(preview.willUpdate()).hasSize(1);
        WorkspaceMemoryArchiveService.FileDiff diff = preview.willUpdate().get(0);
        assertThat(diff.filename()).isEqualTo("MEMORY.md");
        assertThat(diff.oldSize()).isEqualTo(3L);   // "old"
        assertThat(diff.newSize()).isEqualTo(23L);
        assertThat(diff.oldHash()).isNotBlank().isNotEqualTo(diff.newHash());
    }

    // ---------- apply ----------

    @Test
    @DisplayName("Apply writes exactly the create + update set the preview promised")
    void applyWritesPromisedSet() throws Exception {
        wireAgent(1L, 10L);
        when(workspaceFileService.getFile(1L, "MEMORY.md"))
                .thenReturn(stubFile("MEMORY.md", "old"));
        when(workspaceFileService.getFile(1L, "PROFILE.md"))
                .thenReturn(stubFile("PROFILE.md", "same"));
        when(workspaceFileService.getFile(1L, "memory/2026-05-12.md"))
                .thenReturn(null);

        byte[] zip = makeZip(Map.of(
                "MEMORY.md", "new memory",
                "PROFILE.md", "same",                   // unchanged → skip
                "memory/2026-05-12.md", "day 12",
                "secrets.bin", "blob"));                // whitelist reject

        WorkspaceMemoryArchiveService.ImportResult result =
                service.apply(1L, 10L, zip);

        assertThat(result.applied()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(2);  // PROFILE unchanged + secrets.bin not whitelisted

        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(workspaceFileService, times(2)).saveFile(eq(1L), nameCap.capture(), bodyCap.capture());
        assertThat(nameCap.getAllValues()).containsExactlyInAnyOrder("MEMORY.md", "memory/2026-05-12.md");
        // Unchanged PROFILE.md and out-of-whitelist secrets.bin must NEVER be written.
        assertThat(nameCap.getAllValues()).doesNotContain("PROFILE.md", "secrets.bin");
    }

    // ---------- ZIP bomb defenses ----------

    @Test
    @DisplayName("Too many entries → 400, no writes")
    void tooManyEntriesRejected() throws Exception {
        wireAgent(1L, 10L);
        // Use the ZIP API directly so we can write duplicate-named entries
        // past the cap; LinkedHashMap dedupes keys before we'd ever reach
        // MAX_ENTRIES. (The bomb defence is enforced on archive-level entry
        // count, not unique names.)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (int i = 0; i < WorkspaceMemoryArchiveService.MAX_ENTRIES + 5; i++) {
                zip.putNextEntry(new ZipEntry("dup-entry-" + i + ".txt"));
                zip.write(new byte[]{'x'});
                zip.closeEntry();
            }
        }

        assertThatThrownBy(() -> service.apply(1L, 10L, baos.toByteArray()))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("too many entries");
        verify(workspaceFileService, never()).saveFile(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Single oversized entry → 400, no writes")
    void oversizedEntryRejected() throws Exception {
        wireAgent(1L, 10L);
        // One entry past the per-entry cap.
        byte[] huge = new byte[(int) (WorkspaceMemoryArchiveService.MAX_ENTRY_BYTES + 100)];
        byte[] zip = makeZip(Map.of("MEMORY.md", new String(huge, StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.apply(1L, 10L, zip))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("size limit");
        verify(workspaceFileService, never()).saveFile(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Total decompressed bytes over cap → 400, no writes")
    void totalSizeRejected() throws Exception {
        wireAgent(1L, 10L);
        // Twenty 900 KB entries ≈ 18 MB total — past the 16 MB cap.
        int entrySize = 900 * 1024;
        Map<String, String> bomb = new LinkedHashMap<>();
        String body = new String(new byte[entrySize], StandardCharsets.UTF_8);
        for (int i = 0; i < 20; i++) {
            bomb.put("memory/2026-05-" + String.format("%02d", (i % 28) + 1) + ".md", body);
        }
        byte[] zip = makeZip(bomb);

        assertThatThrownBy(() -> service.apply(1L, 10L, zip))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("total size");
        verify(workspaceFileService, never()).saveFile(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Empty / null archive → 400")
    void emptyArchiveRejected() {
        wireAgent(1L, 10L);
        assertThatThrownBy(() -> service.apply(1L, 10L, new byte[0]))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("Empty archive");
        assertThatThrownBy(() -> service.apply(1L, 10L, null))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("Empty archive");
    }

    // ---------- path traversal / weird names ----------

    @Test
    @DisplayName("Path-traversal style filenames land in skip, never in saveFile")
    void pathTraversalSkipped() throws Exception {
        wireAgent(1L, 10L);
        byte[] zip = makeZip(Map.of(
                "../../../etc/passwd", "root:x:0",
                "memory/../etc/passwd", "root:x:0",
                "memory\\2026-05-12.md", "windows-separator",
                "/absolute/path.md", "absolute"));

        WorkspaceMemoryArchiveService.ImportPreview preview =
                service.previewImport(1L, 10L, zip);
        assertThat(preview.willCreate()).isEmpty();
        assertThat(preview.willUpdate()).isEmpty();
        assertThat(preview.willSkip())
                .extracting(WorkspaceMemoryArchiveService.SkipEntry::reason)
                .allSatisfy(r -> assertThat(r).isEqualTo("not in whitelist"));
    }

    @Test
    @DisplayName("Invalid date in memory/YYYY-MM-DD.md → skip")
    void invalidDailyFilenameSkipped() throws Exception {
        wireAgent(1L, 10L);
        byte[] zip = makeZip(Map.of(
                "memory/2026-13-99.md", "fake date but regex passes? must reject",
                "memory/notes.md", "wrong name shape",
                "memory/2026-05-12.txt", "wrong extension"));

        WorkspaceMemoryArchiveService.ImportPreview preview =
                service.previewImport(1L, 10L, zip);
        // The regex matches digit shape but the values 13-99 happen to pass
        // \d{4}-\d{2}-\d{2} — guarded by future enhancement. For v1 we only
        // pin the literal-name / extension / non-digit rejections. (See
        // RFC §2.3.1 — date-range validation deferred.)
        assertThat(preview.willSkip())
                .extracting(WorkspaceMemoryArchiveService.SkipEntry::filename)
                .contains("memory/notes.md", "memory/2026-05-12.txt");
    }

    // ---------- helpers ----------

    private void wireAgent(Long agentId, Long workspaceId) {
        when(agentService.getAgent(agentId)).thenReturn(makeAgent(agentId, workspaceId));
    }

    private static AgentEntity makeAgent(Long id, Long workspaceId) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName("test-agent");
        a.setEnabled(true);
        a.setWorkspaceId(workspaceId);
        return a;
    }

    private static WorkspaceFileEntity stubMeta(String filename) {
        WorkspaceFileEntity e = new WorkspaceFileEntity();
        e.setFilename(filename);
        return e;
    }

    private static WorkspaceFileEntity stubFile(String filename, String content) {
        WorkspaceFileEntity e = stubMeta(filename);
        e.setContent(content);
        return e;
    }

    private static byte[] makeZip(Map<String, String> entries) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                zip.putNextEntry(entry);
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return baos.toByteArray();
    }

    private static Map<String, byte[]> readZip(byte[] data) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            byte[] buf = new byte[4096];
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buf)) > 0) body.write(buf, 0, n);
                out.put(entry.getName(), body.toByteArray());
                zip.closeEntry();
            }
        }
        return out;
    }
}
