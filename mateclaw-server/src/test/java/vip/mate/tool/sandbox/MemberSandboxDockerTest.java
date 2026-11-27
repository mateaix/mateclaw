package vip.mate.tool.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MemberSandboxDockerTest {
    @TempDir Path directory;

    @Test void pythonCannotReadOrWriteOutsideMemberMount() throws Exception {
        assumeTrue(available("docker", "info"), "Local Docker daemon is unavailable");
        assumeTrue(available("docker", "image", "inspect", "mateclaw-sandbox:local"), "Build docker/member-sandbox image first");
        Path member = Files.createDirectory(directory.resolve("member")).toRealPath();
        Path peer = Files.writeString(directory.resolve("peer-secret"), "PRIVATE").toRealPath();
        Files.createSymbolicLink(member.resolve("escape"), peer);
        String code = "import pathlib,sys\n"
                + "for p in [pathlib.Path(sys.argv[1]), pathlib.Path('escape'),\n"
                + "          pathlib.Path('..') / pathlib.Path(sys.argv[1]).name,\n"
                + "          pathlib.Path(bytes.fromhex(sys.argv[1].encode().hex()).decode())]:\n"
                + " try:\n  print('LEAK:' + p.read_text())\n"
                + " except OSError:\n  print('BLOCKED')\n"
                + "try:\n pathlib.Path(sys.argv[1]).write_text('CORRUPTED')\n print('WRITE_ESCAPED')\n"
                + "except OSError:\n print('WRITE_BLOCKED')\n"
                + "pathlib.Path('own-result').write_text('ok')\n";
        var result = new MemberSandboxExecutor("mateclaw-sandbox:local")
                .executeCode("python", code, member, List.of(peer.toString()), Map.of(), 20);
        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("BLOCKED\nBLOCKED\nBLOCKED\nBLOCKED\nWRITE_BLOCKED\n", result.stdout());
        assertEquals("PRIVATE", Files.readString(peer));
        assertEquals("ok", Files.readString(member.resolve("own-result")));
        try (var files = Files.list(member)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".mc-code-")));
        }
    }

    private static boolean available(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) { return false; }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
