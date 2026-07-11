package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for issues #36 / #507: a conversation id like
 * {@code cron:<jobId>} or {@code wecom:<user>} carries a colon, which is illegal
 * in a Windows path segment. The old behaviour caught the resulting
 * {@code InvalidPathException} and silently skipped cleanup; the fix instead
 * sanitizes the id to a filesystem-safe segment so the attachment directory is
 * both written and cleaned consistently on every OS.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceCleanAttachmentFilesTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private AgentMapper agentMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private ChatUploadLocationResolver chatUploadLocationResolver;

    @InjectMocks private ConversationService service;

    private Path createdDir;

    @BeforeEach
    void stubResolver() {
        // cleanAttachmentFiles now walks sanitized conversation dirs. Mirror the
        // real resolver: {legacy-default-root}/{sanitizeSegment(id)}.
        when(chatUploadLocationResolver.resolveCandidateConversationDirs(any()))
                .thenAnswer(inv -> List.of(Paths.get("data", "chat-uploads")
                        .resolve(ChatUploadLocationResolver.sanitizeSegment(inv.getArgument(0)))));
    }

    @AfterEach
    void cleanup() throws IOException {
        if (createdDir != null && Files.exists(createdDir)) {
            try (Stream<Path> walk = Files.walk(createdDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    @DisplayName("colon-bearing id (cron:jobId / wecom:xxx) is sanitized and its dir is cleaned, not skipped")
    void colonIdIsSanitizedAndCleaned() throws IOException {
        // The ':' would throw InvalidPathException as a raw Windows path segment;
        // the service must sanitize it and clean the sanitized dir — never throw,
        // never silently skip (the pre-fix bug from issue #36).
        String convId = "cron:job-" + UUID.randomUUID();
        Path uploadRoot = Paths.get("data", "chat-uploads");
        createdDir = uploadRoot.resolve(ChatUploadLocationResolver.sanitizeSegment(convId));
        Files.createDirectories(createdDir);
        Files.writeString(createdDir.resolve("a.txt"), "hello");

        assertThatCode(() -> service.cleanAttachmentFiles(convId)).doesNotThrowAnyException();
        assertThat(Files.exists(createdDir)).isFalse();
    }

    @Test
    @DisplayName("legal id with a real attachment dir is still cleaned (happy path)")
    void legalIdHappyPathStillCleans() throws IOException {
        // UUID-shaped id matches what the web channel uses; sanitize is a no-op.
        String convId = "test-" + UUID.randomUUID();
        Path uploadRoot = Paths.get("data", "chat-uploads");
        createdDir = uploadRoot.resolve(convId);
        Files.createDirectories(createdDir);
        Files.writeString(createdDir.resolve("a.txt"), "hello");
        Path nested = createdDir.resolve("nested");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("b.txt"), "world");

        service.cleanAttachmentFiles(convId);

        assertThat(Files.exists(createdDir)).isFalse();
    }
}
