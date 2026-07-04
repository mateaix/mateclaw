package vip.mate.workspace.core.service;

import vip.mate.agent.AgentService;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.config.ChatUploadProperties;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;

/**
 * Test helper that builds a {@link ChatUploadLocationResolver} whose default
 * upload root points at a caller-chosen directory. Dependencies are Mockito
 * mocks — when neither a workspace nor an agent base path is configured (the
 * common unit-test case), the resolver never consults them and just returns
 * {@link ChatUploadLocationResolver#defaultRoot()}.
 *
 * <p>Production code paths that DO resolve against a workspace/agent base path
 * should configure the mocks via the accessors below.
 */
public final class ChatUploadLocationResolverTestSupport {

    private ChatUploadLocationResolverTestSupport() {}

    /**
     * Build a resolver whose {@link ChatUploadLocationResolver#defaultRoot()}
     * is {@code defaultDir}, with mocked {@link ConversationMapper} /
     * {@link WorkspaceService} / {@link AgentService}.
     */
    public static ChatUploadLocationResolver withDefaultRoot(Path defaultDir) {
        ChatUploadProperties props = new ChatUploadProperties();
        props.setBaseDir(defaultDir.toAbsolutePath().normalize().toString());
        return new ChatUploadLocationResolver(
                mock(ConversationMapper.class),
                mock(WorkspaceService.class),
                props,
                mock(AgentService.class));
    }

    /**
     * Build a resolver whose default root is the legacy {@code data/chat-uploads}
     * (matching out-of-the-box behaviour), with mocked dependencies.
     */
    public static ChatUploadLocationResolver legacyDefault() {
        return withDefaultRoot(Path.of("data", "chat-uploads"));
    }
}
