package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the optional OfficeCLI adapter introduced by issue #583. */
class OfficeCliToolTest {

    private static final Pattern GENERATED_ID = Pattern.compile(
            "/api/v1/files/generated/([a-zA-Z0-9-]+)");

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private GeneratedFileCache cache;
    private Path fakeCli;

    @BeforeEach
    void setUp() throws Exception {
        WorkspacePathGuard.setDefaultRoot(tempDir.toString());
        cache = new GeneratedFileCache(tempDir.resolve("cache"));
        fakeCli = tempDir.resolve("officecli-fake");
        Files.writeString(fakeCli, """
                #!/bin/sh
                command="$1"
                shift
                case "$command" in
                  batch)
                    file="$1"
                    printf '\nEDITED-BY-OFFICECLI' >> "$file"
                    printf '{"ok":true,"command":"batch"}\n'
                    ;;
                  merge)
                    input="$1"
                    output="$2"
                    cp "$input" "$output"
                    printf '\nMERGED-BY-OFFICECLI' >> "$output"
                    printf '{"ok":true,"command":"merge"}\n'
                    ;;
                  validate)
                    printf '{"valid":true}\n'
                    ;;
                  view)
                    input="$1"
                    mode="$2"
                    shift 2
                    output=""
                    while [ "$#" -gt 0 ]; do
                      if [ "$1" = "-o" ]; then output="$2"; shift 2; else shift; fi
                    done
                    if [ -n "$output" ]; then
                      printf 'rendered:%s' "$mode" > "$output"
                    elif [ "$mode" = "html" ] || [ "$mode" = "svg" ]; then
                      printf 'rendered:%s' "$mode"
                    else
                      printf '{"mode":"%s","source":"%s"}\n' "$mode" "$input"
                    fi
                    ;;
                  *)
                    printf 'unsupported fake command\n' >&2
                    exit 2
                    ;;
                esac
                """, StandardCharsets.UTF_8);
        assertTrue(fakeCli.toFile().setExecutable(true));
    }

    @AfterEach
    void tearDown() {
        WorkspacePathGuard.setDefaultRoot(null);
    }

    @Test
    @DisplayName("batch edits a scratch copy, preserves the source, and returns a cached download")
    void batchIsCopyOnWriteAndDownloadable() throws Exception {
        byte[] original = "PK-original-docx".getBytes(StandardCharsets.UTF_8);
        Path source = Files.write(tempDir.resolve("source.docx"), original);
        OfficeCliTool tool = new OfficeCliTool(cache, mapper, fakeCli.toString());

        String result = tool.office_document(
                "batch", source.toString(), null,
                "[{\"command\":\"set\",\"path\":\"/body/p[1]\",\"props\":{\"text\":\"new\"}}]",
                "客户/报告.docx", 10, null);

        JsonNode json = mapper.readTree(result);
        assertTrue(json.path("success").asBoolean(), result);
        assertArrayEquals(original, Files.readAllBytes(source), "source document must never be mutated");

        GeneratedFileCache.Entry entry = cachedEntry(json.path("generatedFile").asText());
        assertEquals("客户_报告.docx", entry.filename());
        assertTrue(new String(entry.bytes(), StandardCharsets.UTF_8).contains("EDITED-BY-OFFICECLI"));
    }

    @Test
    @DisplayName("render returns the requested preview artifact through GeneratedFileCache")
    void renderProducesDownload() throws Exception {
        Path source = Files.writeString(tempDir.resolve("slides.pptx"), "PK-pptx");
        OfficeCliTool tool = new OfficeCliTool(cache, mapper, fakeCli.toString());

        String result = tool.office_document(
                "render", source.toString(), "screenshot", null,
                "预览.png", 10, null);

        JsonNode json = mapper.readTree(result);
        assertTrue(json.path("success").asBoolean(), result);
        GeneratedFileCache.Entry entry = cachedEntry(json.path("generatedFile").asText());
        assertEquals("预览.png", entry.filename());
        assertEquals("image/png", entry.mimeType());
        assertEquals("rendered:screenshot", new String(entry.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("stdout render modes are captured as artifacts instead of diagnostics")
    void stdoutRenderProducesDownload() throws Exception {
        Path source = Files.writeString(tempDir.resolve("slides.pptx"), "PK-pptx");
        OfficeCliTool tool = new OfficeCliTool(cache, mapper, fakeCli.toString());

        JsonNode result = mapper.readTree(tool.office_document(
                "render", source.toString(), "svg", null, null, 10, null));

        assertTrue(result.path("success").asBoolean(), result.toString());
        GeneratedFileCache.Entry entry = cachedEntry(result.path("generatedFile").asText());
        assertEquals("image/svg+xml", entry.mimeType());
        assertEquals("rendered:svg", new String(entry.bytes(), StandardCharsets.UTF_8));
        assertEquals("", result.path("stdout").asText());
    }

    @Test
    @DisplayName("read-only validation returns structured stdout without generating a file")
    void validateIsReadOnly() throws Exception {
        Path source = Files.writeString(tempDir.resolve("book.xlsx"), "PK-xlsx");
        OfficeCliTool tool = new OfficeCliTool(cache, mapper, fakeCli.toString());

        JsonNode result = mapper.readTree(tool.office_document(
                "validate", source.toString(), null, null, null, 10, null));

        assertTrue(result.path("success").asBoolean());
        assertTrue(result.path("stdout").asText().contains("\"valid\":true"));
        assertFalse(result.has("generatedFile"));
    }

    @Test
    @DisplayName("invalid payloads and unsupported formats fail before a subprocess mutates anything")
    void rejectsInvalidRequests() throws Exception {
        Path office = Files.writeString(tempDir.resolve("a.docx"), "PK-docx");
        Path text = Files.writeString(tempDir.resolve("a.txt"), "text");
        OfficeCliTool tool = new OfficeCliTool(cache, mapper, fakeCli.toString());

        JsonNode badPayload = mapper.readTree(tool.office_document(
                "batch", office.toString(), null, "{}", null, 10, null));
        JsonNode badFormat = mapper.readTree(tool.office_document(
                "validate", text.toString(), null, null, null, 10, null));

        assertFalse(badPayload.path("success").asBoolean());
        assertTrue(badPayload.path("error").asText().contains("JSON array"));
        assertFalse(badFormat.path("success").asBoolean());
        assertTrue(badFormat.path("error").asText().contains(".docx"));
    }

    @Test
    @DisplayName("batch rejects raw XML, external media, and host filesystem references")
    void rejectsUnsafeBatchSurface() throws Exception {
        Path office = Files.writeString(tempDir.resolve("a.docx"), "PK-docx");
        OfficeCliTool tool = new OfficeCliTool(cache, mapper, fakeCli.toString());

        JsonNode raw = mapper.readTree(tool.office_document(
                "batch", office.toString(), null,
                "[{\"command\":\"raw-set\",\"path\":\"/body\",\"xml\":\"<x/>\"}]",
                null, 10, null));
        JsonNode media = mapper.readTree(tool.office_document(
                "batch", office.toString(), null,
                "[{\"command\":\"add\",\"path\":\"/body\",\"type\":\"image\",\"props\":{\"source\":\"/etc/passwd\"}}]",
                null, 10, null));
        JsonNode hostPath = mapper.readTree(tool.office_document(
                "batch", office.toString(), null,
                "[{\"command\":\"set\",\"path\":\"/body/p[1]\",\"props\":{\"source\":\"C:\\\\secret.txt\"}}]",
                null, 10, null));

        assertTrue(raw.path("error").asText().contains("Unsupported batch command"));
        assertTrue(media.path("error").asText().contains("External media"));
        assertTrue(hostPath.path("error").asText().contains("filesystem reference"));
    }

    @Test
    @DisplayName("timeout terminates the native process and reports a bounded failure")
    void timeoutIsEnforced() throws Exception {
        Path sleepy = tempDir.resolve("officecli-sleepy");
        Files.writeString(sleepy, "#!/bin/sh\nsleep 10\n", StandardCharsets.UTF_8);
        assertTrue(sleepy.toFile().setExecutable(true));
        Path source = Files.writeString(tempDir.resolve("slow.docx"), "PK-docx");
        OfficeCliTool tool = new OfficeCliTool(cache, mapper, sleepy.toString());

        long started = System.nanoTime();
        JsonNode result = mapper.readTree(tool.office_document(
                "validate", source.toString(), null, null, null, 1, null));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("timedOut").asBoolean());
        assertTrue(elapsedMillis < 5_000, "timeout took too long: " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("a missing binary is reported as setup-required instead of an opaque exception")
    void missingBinaryReportsSetupRequired() throws Exception {
        Path source = Files.writeString(tempDir.resolve("a.docx"), "PK-docx");
        OfficeCliTool tool = new OfficeCliTool(cache, mapper,
                tempDir.resolve("missing-officecli").toString());

        JsonNode result = mapper.readTree(tool.office_document(
                "validate", source.toString(), null, null, null, 10, null));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("setupRequired").asBoolean(), result.toString());
    }

    private GeneratedFileCache.Entry cachedEntry(String generatedFileText) {
        Matcher matcher = GENERATED_ID.matcher(generatedFileText);
        assertTrue(matcher.find(), generatedFileText);
        return cache.get(matcher.group(1)).orElseThrow();
    }
}
