package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.agent.AgentService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.tool.document.preview.OfficePreviewService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Route + gating tests for {@code GET /files/{conversationId}/{storedName}/preview}.
 *
 * <p>Verifies the preview route is registered as its own handler (not swallowed
 * by the raw {@code /files/{conversationId}/{storedName:.+}} mapping) and that
 * every gate — ownership, convertibility, converter availability, file presence
 * — maps to the intended status code. Standalone MockMvc so no Spring context
 * or running server is needed.
 */
class ChatControllerPreviewRouteTest {

    private ConversationService conversationService;
    private ChatUploadLocationResolver uploadLocationResolver;
    private OfficePreviewService officePreviewService;
    private MockMvc mockMvc;

    private static final String CONV = "wecom:someone";
    private static final String STORED = "1777_deck.pptx";
    private static final String URL = "/api/v1/chat/files/" + CONV + "/" + STORED + "/preview";

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        uploadLocationResolver = mock(ChatUploadLocationResolver.class);
        officePreviewService = mock(OfficePreviewService.class);

        ChatController controller = new ChatController(
                mock(AgentService.class),
                conversationService,
                mock(ApprovalWorkflowService.class),
                mock(ChatStreamTracker.class),
                new ObjectMapper(),
                mock(vip.mate.memory.event.ConversationCompletionPublisher.class),
                mock(MemoryOwnerResolver.class),
                uploadLocationResolver,
                officePreviewService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
    }

    @Test
    void notOwner_returns403() throws Exception {
        when(conversationService.isConversationOwner(eq(CONV), anyString())).thenReturn(false);
        mockMvc.perform(MockMvcRequestBuilders.get(URL).principal(admin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonConvertibleFormat_returns415() throws Exception {
        when(conversationService.isConversationOwner(eq(CONV), anyString())).thenReturn(true);
        when(officePreviewService.isConvertible(STORED)).thenReturn(false);
        mockMvc.perform(MockMvcRequestBuilders.get(URL).principal(admin()))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void converterUnavailable_returns501() throws Exception {
        when(conversationService.isConversationOwner(eq(CONV), anyString())).thenReturn(true);
        when(officePreviewService.isConvertible(STORED)).thenReturn(true);
        when(officePreviewService.isAvailable()).thenReturn(false);
        mockMvc.perform(MockMvcRequestBuilders.get(URL).principal(admin()))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void fileMissing_returns404() throws Exception {
        when(conversationService.isConversationOwner(eq(CONV), anyString())).thenReturn(true);
        when(officePreviewService.isConvertible(STORED)).thenReturn(true);
        when(officePreviewService.isAvailable()).thenReturn(true);
        when(uploadLocationResolver.resolveCandidateConversationDirs(CONV)).thenReturn(List.of());
        mockMvc.perform(MockMvcRequestBuilders.get(URL).principal(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void convertibleAndPresent_returns200Pdf() throws Exception {
        Path dir = Files.createTempDirectory("mc_preview_route_");
        try {
            Path src = dir.resolve(STORED);
            Files.write(src, new byte[]{1, 2, 3});

            when(conversationService.isConversationOwner(eq(CONV), anyString())).thenReturn(true);
            when(officePreviewService.isConvertible(STORED)).thenReturn(true);
            when(officePreviewService.isAvailable()).thenReturn(true);
            when(uploadLocationResolver.resolveCandidateConversationDirs(CONV)).thenReturn(List.of(dir));
            byte[] pdf = "%PDF-1.4 fake".getBytes();
            when(officePreviewService.renderPdf(any(Path.class))).thenReturn(pdf);

            mockMvc.perform(MockMvcRequestBuilders.get(URL).principal(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        } finally {
            Files.deleteIfExists(dir.resolve(STORED));
            Files.deleteIfExists(dir);
        }
    }
}
