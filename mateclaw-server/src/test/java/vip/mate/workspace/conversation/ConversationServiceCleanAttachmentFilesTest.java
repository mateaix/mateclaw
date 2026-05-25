package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression coverage for issue #36: deleting a CRON-task conversation throws
 * {@code InvalidPathException} on Windows because the conversation id
 * ("cron:&lt;jobId&gt;") contains a colon, which is illegal in Windows path
 * segments. The exception bubbles out of the {@code @Transactional}
 * {@code deleteConversation}, rolling back the row deletes and leaving the
 * user unable to remove the entry.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceCleanAttachmentFilesTest {

    /** NUL byte: rejected by Paths.get on every OS, so it portably triggers
     * the same InvalidPathException branch the colon hits on Windows. Built
     * via String.valueOf((char) 0) so the source text contains no embedded
     * NUL (which would be invisible in diff tools). */
    private static final String UNREPRESENTABLE_ID = "bad" + (char) 0 + "id";

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private AgentMapper agentMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private ConversationService service;

    private Path createdDir;

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
    @DisplayName("conversation id that yields an unrepresentable path is skipped, not thrown")
    void unrepresentablePathIdIsSkipped() {
        // Precondition: confirm the id really does break Paths.resolve on
        // this JDK / OS. If a future JDK ever accepts the NUL byte, the
        // service-level assertion below would silently pass without
        // exercising the catch branch we are guarding — fail loudly here
        // instead.
        assertThatThrownBy(() -> Paths.get("data", "chat-uploads").resolve(UNREPRESENTABLE_ID))
                .isInstanceOf(InvalidPathException.class);

        // Without the catch in cleanAttachmentFiles, this would propagate
        // InvalidPathException out of the @Transactional deleteConversation,
        // rolling back the row deletes — the user-visible bug from issue #36.
        assertThatCode(() -> service.cleanAttachmentFiles(UNREPRESENTABLE_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("legal id with a real attachment dir is still cleaned (happy path)")
    void legalIdHappyPathStillCleans() throws IOException {
        // UUID-shaped id matches what the web channel actually uses, so the
        // resolve() succeeds on every OS and the walk/delete loop runs.
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
