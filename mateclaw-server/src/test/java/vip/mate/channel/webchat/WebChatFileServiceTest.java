package vip.mate.channel.webchat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation + isolation contract for {@link WebChatFileService}. WebChat
 * uploads come from untrusted external visitors, so these pin: extension
 * whitelist, size cap, disabled-switch, per-conversation ownership of staged
 * ids, and traversal-safe resolution.
 */
class WebChatFileServiceTest {

    private static final String CONV = "webchat:abcd1234:visitor-1";

    private WebChatFileService service(boolean enabled, long maxMb, String exts) {
        return new WebChatFileService(enabled, maxMb, exts, 50, 200);
    }

    private WebChatFileService service(boolean enabled, long maxMb, String exts,
                                       int maxFiles, long maxTotalMb) {
        return new WebChatFileService(enabled, maxMb, exts, maxFiles, maxTotalMb);
    }

    @AfterEach
    void cleanup() throws IOException {
        Path dir = Paths.get("data", "chat-uploads", CONV);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    @DisplayName("rejects a disallowed extension")
    void rejectsDisallowedExtension() {
        WebChatFileService svc = service(true, 20, "png,txt");
        MockMultipartFile evil = new MockMultipartFile("file", "evil.exe",
                "application/octet-stream", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> svc.store(CONV, evil))
                .isInstanceOf(WebChatFileService.UploadRejectedException.class);
    }

    @Test
    @DisplayName("rejects an oversized file")
    void rejectsOversized() {
        WebChatFileService svc = service(true, 1, "png");
        byte[] big = new byte[2 * 1024 * 1024]; // 2MB > 1MB cap
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);
        assertThatThrownBy(() -> svc.store(CONV, file))
                .isInstanceOf(WebChatFileService.UploadRejectedException.class);
    }

    @Test
    @DisplayName("rejects when disabled")
    void rejectsWhenDisabled() {
        WebChatFileService svc = service(false, 20, "png");
        MockMultipartFile file = new MockMultipartFile("file", "ok.png", "image/png", new byte[]{1});
        assertThatThrownBy(() -> svc.store(CONV, file))
                .isInstanceOf(WebChatFileService.UploadRejectedException.class);
    }

    @Test
    @DisplayName("accepts allowed file; consume is one-shot and conversation-scoped")
    void acceptsAndConsumeIsScoped() throws IOException {
        WebChatFileService svc = service(true, 20, "png,txt");
        MockMultipartFile file = new MockMultipartFile("file", "hello.txt", "text/plain",
                "hi".getBytes());

        WebChatFileService.StagedFile stored = svc.store(CONV, file);
        assertThat(stored.originalName()).isEqualTo("hello.txt");
        assertThat(stored.conversationId()).isEqualTo(CONV);

        // Foreign conversation can't consume it.
        assertThat(svc.consume("webchat:abcd1234:other", stored.storedName())).isEmpty();
        // Owning conversation can — exactly once.
        assertThat(svc.consume(CONV, stored.storedName())).isPresent();
        assertThat(svc.consume(CONV, stored.storedName())).isEmpty();

        // Bytes survive on disk for download after consume.
        assertThat(svc.resolve(CONV, stored.storedName())).isPresent();
    }

    @Test
    @DisplayName("rejects once the per-conversation file-count quota is hit")
    void rejectsOverFileCountQuota() throws IOException {
        WebChatFileService svc = service(true, 20, "txt", 2, 200); // max 2 files
        svc.store(CONV, new MockMultipartFile("file", "a.txt", "text/plain", "a".getBytes()));
        svc.store(CONV, new MockMultipartFile("file", "b.txt", "text/plain", "b".getBytes()));
        assertThatThrownBy(() -> svc.store(CONV,
                new MockMultipartFile("file", "c.txt", "text/plain", "c".getBytes())))
                .isInstanceOf(WebChatFileService.UploadRejectedException.class);
    }

    @Test
    @DisplayName("resolve is traversal-safe")
    void resolveRejectsTraversal() {
        WebChatFileService svc = service(true, 20, "png");
        Optional<Path> escaped = svc.resolve(CONV, "../../../../etc/passwd");
        assertThat(escaped).isEmpty();
    }
}
