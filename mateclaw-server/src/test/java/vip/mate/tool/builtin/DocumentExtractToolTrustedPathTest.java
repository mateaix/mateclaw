package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for issue #323: uploading a .docx (or any binary file) to
 * the wiki failed with "No text content available".
 *
 * <p>Root cause: the wiki ingest pipeline stages uploads under its own upload
 * directory (default {@code ./data/wiki-uploads}) and feeds that path back into
 * {@link DocumentExtractTool} for text extraction. With the workspace sandbox
 * enabled (the default), the global fallback root is {@code ./data/workspace} —
 * a <i>sibling</i> of the upload dir. The boundary guard therefore rejected the
 * server's own staged path as "outside workspace boundary", the extractor
 * returned {@code success=false}, and the wiki fell back to a null body.
 *
 * <p>The fix routes the internal, server-controlled path through
 * {@link DocumentExtractTool#extractTrustedDocument} which skips the LLM-oriented
 * boundary guard. These tests pin both halves: the guarded tool entry still
 * rejects an out-of-sandbox path (demonstrating the bug), and the trusted entry
 * extracts it successfully (verifying the fix).
 */
@DisabledOnOs(OS.WINDOWS) // POSIX-style absolute paths / sandbox roots in these cases
class DocumentExtractToolTrustedPathTest {

    private static final String TOKEN = "REGRESSION_TOKEN_323";

    private final DocumentExtractTool tool = new DocumentExtractTool();

    @TempDir
    Path sandboxRoot; // stands in for ./data/workspace

    @TempDir
    Path uploadDir; // stands in for ./data/wiki-uploads (a sibling, outside the sandbox)

    private Path docx;

    @BeforeEach
    void setup() throws Exception {
        // Simulate the out-of-the-box state: sandbox enabled with a fallback root,
        // no per-conversation workspace configured.
        ToolExecutionContext.clear();
        WorkspacePathGuard.setDefaultRoot(sandboxRoot.toString());
        docx = uploadDir.resolve(System.currentTimeMillis() + "_regression-323.docx");
        writeMinimalDocx(docx, TOKEN + " hello world");
    }

    @AfterEach
    void teardown() {
        ToolExecutionContext.clear();
        WorkspacePathGuard.setDefaultRoot(null);
        WorkspacePathGuard.setSkillRoot(null);
    }

    @Test
    @DisplayName("Guarded tool entry rejects the staged upload path (the #323 failure)")
    void guardedEntry_rejectsOutsideSandbox() {
        JSONObject result = JSONUtil.parseObj(
                tool.extract_document_text(docx.toString(), null, null));
        assertThat(result.getBool("success", false))
                .as("an upload-dir path sits outside the sandbox root and must be blocked by the guard")
                .isFalse();
    }

    @Test
    @DisplayName("Trusted entry extracts the staged upload path (the #323 fix)")
    void trustedEntry_extractsOutsideSandbox() {
        JSONObject result = JSONUtil.parseObj(
                tool.extractTrustedDocument(docx.toString(), null));
        assertThat(result.getBool("success", false))
                .as("server-managed path must bypass the sandbox and extract successfully")
                .isTrue();
        assertThat(result.getStr("text")).contains(TOKEN);
    }

    /**
     * Write a minimal but valid-enough .docx: a ZIP whose {@code word/document.xml}
     * carries the text inside {@code <w:t>} runs. This is exactly what the pure-Java
     * ZIP-XML extractor in {@link DocumentExtractTool} reads, so the test needs no
     * external tools (textutil / pandoc / libreoffice) to be installed.
     */
    private static void writeMinimalDocx(Path target, String body) throws Exception {
        String contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>""";
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body>
                </w:document>""".formatted(body);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write(contentTypes.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
