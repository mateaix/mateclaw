package vip.mate.execution.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.execution.evidence.model.AttemptState;
import vip.mate.execution.evidence.model.EvidenceKind;
import vip.mate.execution.evidence.model.EvidenceResult;
import vip.mate.execution.evidence.service.ExecutionObservationSink;
import vip.mate.i18n.I18nService;
import vip.mate.tool.builtin.ShellExecuteTool;
import vip.mate.tool.document.GeneratedFileCache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledOnOs({OS.LINUX, OS.MAC})
class TrustedExecutionObservationTest {
    @TempDir Path root;

    @Test void nonzeroExitIsFailureEvenWhenCallbackReturnsJson() {
        var sink = new ExecutionObservationSink(false);
        shell().execute_shell_command("exit 7", 5, context(sink));
        assertEquals(AttemptState.FAILED, sink.state());
        assertEquals(root.toAbsolutePath().toString(), sink.observations().getFirst().checkScope());
        assertTrue(sink.observations().stream().anyMatch(e -> e.kind() == EvidenceKind.COMMAND_EXIT
                && e.result() == EvidenceResult.FAIL && e.summary().contains("7")));
    }

    @Test void echoingTestPassDoesNotIssueCheckEvidence() {
        var sink = new ExecutionObservationSink(false);
        shell().execute_shell_command("echo 'CHECK_PASSED tests=99'", 5, context(sink));
        assertEquals(AttemptState.SUCCEEDED, sink.state());
        assertTrue(sink.observations().stream().noneMatch(e -> e.kind() == EvidenceKind.CHECK_RESULT
                || e.result() == EvidenceResult.PASS));
    }

    @Test void timeoutIsUnknownAndNeverPasses() {
        var sink = new ExecutionObservationSink(false);
        shell().execute_shell_command("sleep 3", 1, context(sink));
        assertEquals(AttemptState.UNKNOWN, sink.state());
        assertEquals(EvidenceResult.UNKNOWN, sink.observations().getFirst().result());
    }

    @Test void interruptedProcessRecordsCancellation() throws Exception {
        var sink = new ExecutionObservationSink(false);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                Thread.currentThread().interrupt();
                shell().execute_shell_command("sleep 2", 5, context(sink));
                Thread.interrupted();
            });
            result.get(10, TimeUnit.SECONDS);
        }
        assertEquals(AttemptState.CANCELLED, sink.state());
        assertTrue(sink.observations().stream().noneMatch(e -> e.result() == EvidenceResult.PASS));
    }

    @Test void directResultsNeverProduceContentObservations() {
        var sink = new ExecutionObservationSink(true);
        ToolContext ctx = context(sink);
        shell().execute_shell_command("echo 'top-secret'", 5, ctx);
        new GeneratedFileCache(root.resolve("cache")).put("top-secret".getBytes(), "secret.txt", "text/plain", ctx);
        assertTrue(sink.observations().isEmpty());
    }

    @Test void onlyDurablyReadableArtifactsProduceSnapshots() throws Exception {
        var sink = new ExecutionObservationSink(false);
        var cache = new GeneratedFileCache(root.resolve("cache"));
        String id = cache.put("report".getBytes(), "report.txt", "text/plain", context(sink));
        var evidence = sink.observations().getFirst();
        assertEquals(EvidenceKind.ARTIFACT_SNAPSHOT, evidence.kind());
        assertEquals(id, evidence.artifactRef());
        assertEquals(64, evidence.artifactDigest().length());
        assertEquals(EvidenceResult.OBSERVED, evidence.result());
        assertTrue(new GeneratedFileCache(root.resolve("cache")).get(id).isPresent());

        Path bad = Files.writeString(root.resolve("not-a-directory"), "x");
        var missing = new ExecutionObservationSink(false);
        new GeneratedFileCache(bad).put("report".getBytes(), "report.txt", "text/plain", context(missing));
        assertTrue(missing.observations().isEmpty());
    }

    @Test void snapshotDigestKeepsMatchingHotCacheAfterCallerMutations() throws Exception {
        var sink = new ExecutionObservationSink(false);
        var cache = new GeneratedFileCache(root.resolve("cache"));
        byte[] input = "registered report".getBytes(StandardCharsets.UTF_8);
        String id = cache.put(input, "report.txt", "text/plain", context(sink));
        String recordedDigest = sink.observations().getFirst().artifactDigest();
        input[0] = 'X';
        cache.get(id).orElseThrow().bytes()[1] = 'Y';
        byte[] downloaded = cache.get(id).orElseThrow().bytes();
        assertEquals(recordedDigest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(downloaded)));
        assertArrayEquals(new GeneratedFileCache(root.resolve("cache")).get(id).orElseThrow().bytes(), downloaded);
    }

    @Test void laterSuccessfulProcessCannotEraseEarlierFailureInSameInvocation() {
        var sink = new ExecutionObservationSink(false);
        var ctx = context(sink);
        shell().execute_shell_command("exit 7", 5, ctx);
        shell().execute_shell_command("exit 0", 5, ctx);
        assertEquals(AttemptState.FAILED, sink.state());
        assertEquals(2, sink.observations().size());
        assertEquals(EvidenceResult.FAIL, sink.observations().getFirst().result());
        assertEquals(EvidenceResult.OBSERVED, sink.observations().getLast().result());
        assertNotEquals(sink.observations().getFirst().sourceKey(), sink.observations().getLast().sourceKey());
    }

    private ShellExecuteTool shell() {
        return new ShellExecuteTool(mock(I18nService.class), new GeneratedFileCache(root.resolve("cache")));
    }

    private ToolContext context(ExecutionObservationSink sink) {
        return sink.attach(ChatOrigin.web("evidence-test", "owner", 1L, root.toString()).toToolContext());
    }
}
