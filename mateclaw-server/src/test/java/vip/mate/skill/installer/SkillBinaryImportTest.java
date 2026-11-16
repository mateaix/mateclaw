package vip.mate.skill.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillBinaryImportTest {
    @TempDir Path root;
    private static final byte[] BINARY = {(byte)0x89, 'P', 'N', 'G', 0, (byte)0xff, 13, 10};
    private static final String MD = "---\nname: binary-test\ndescription: binary test\n---\n# Test\n";
    @Test void gitImportPreservesBytesAcrossBuckets() throws Exception {
        Files.writeString(root.resolve("SKILL.md"), MD);
        for (String bucket : new String[]{"references", "scripts", "templates"}) {
            Files.createDirectories(root.resolve(bucket));
            Files.write(root.resolve(bucket + "/image.png"), BINARY);
        }
        git("init"); git("add", "."); git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture");
        var fetcher = new GitSkillFetcher(new vip.mate.skill.runtime.SkillFrontmatterParser(), "");
        var bundle = fetcher.fetch(root.toString(), null, null);
        assertNotNull(bundle);
        for (String bucket : new String[]{"references", "scripts", "templates"})
            assertArrayEquals(BINARY, bundle.files().get(bucket + "/image.png"));
    }
    @Test void zipImportPreservesBinaryAndOriginalTextBytes() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String name : new String[]{"pkg/SKILL.md", "pkg/references/image.png", "pkg/templates/image.png", "pkg/scripts/test.py"}) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.endsWith("SKILL.md") ? MD.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : name.endsWith(".py") ? "print('中文')\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8) : BINARY);
                zip.closeEntry();
            }
        }
        var extracted = ZipSkillFetcher.extract(new ByteArrayInputStream(bytes.toByteArray()));
        assertArrayEquals(BINARY, extracted.files().get("references/image.png"));
        assertArrayEquals(BINARY, extracted.files().get("templates/image.png"));
        assertArrayEquals("print('中文')\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), extracted.files().get("scripts/test.py"));
    }
    private void git(String... args) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes(); assertEquals(0, process.waitFor());
    }
}
