package vip.mate.tool.image.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.channel.AsyncTaskMediaDispatcher;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.tool.image.ImageFileDownloader;
import vip.mate.tool.image.ImageGenerationRequest;
import vip.mate.tool.image.ImageGenerationResult;
import vip.mate.tool.image.ImageGenerationService;
import vip.mate.tool.image.ImageProviderRegistry;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(20)
class MiniMaxImageGenerationTest {
    private final MockWebServer server = new MockWebServer();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SystemSettingsDTO config = new SystemSettingsDTO();
    @TempDir
    Path images;
    private ImageGenerationService service;
    private String endpoint;

    @BeforeEach
    void setUp() throws IOException {
        server.start();
        var provider = new MiniMaxImageProvider(mapper) {
            @Override
            String generationEndpoint(SystemSettingsDTO settings, boolean h3) {
                endpoint = super.generationEndpoint(settings, h3);
                return server.url(URI.create(endpoint).getPath()).toString();
            }
        };
        var settings = mock(SystemSettingService.class);
        config.setImageEnabled(true);
        config.setMinimaxApiKey("test");
        when(settings.getAllSettings()).thenReturn(config);
        var storage = mock(ChatUploadLocationResolver.class);
        when(storage.resolveWriteDir("chat")).thenReturn(images);
        service = new ImageGenerationService(settings, new ImageProviderRegistry(List.of(provider)), null,
                mock(ConversationService.class), new ImageFileDownloader(storage), mapper,
                mock(ChatStreamTracker.class), mock(AsyncTaskMediaDispatcher.class));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private ImageGenerationResult generate(String model, int count) {
        return service.submitGeneration(ImageGenerationRequest.builder().prompt("A sunny garden").model(model)
                .aspectRatio("16:9").count(count).build(), "chat", "user");
    }

    @Test
    void h3ReturnsSuccessfulImagesFromAMixedBatch() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"data":[{"url":"%s"},{"error":{"code":"1027","message":"Image rejected"}}],"output_format":"png"}
                """.formatted(server.url("/garden.png"))));
        server.enqueue(new MockResponse().setBody("image data"));
        var result = generate("MiniMax-H3-Image", 2);
        assertTrue(result.isCompleted(), result.getMessage());
        assertEquals(1, result.getImageUrls().size());
        assertEquals("https://api.minimax.io/v1/images/generations", endpoint);
        var request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertNull(request.getHeader("Content-Length"));
        assertEquals("chunked", request.getHeader("Transfer-Encoding"));
        assertEquals("/v1/images/generations", request.getPath());
        assertEquals("Bearer test", request.getHeader("Authorization"));
        assertEquals(mapper.readTree("""
                {"model":"MiniMax-H3-Image","prompt":"A sunny garden","response_format":"url",
                 "output_format":"png","aspect_ratio":"16:9","n":2}
                """), mapper.readTree(request.getBody().readUtf8()));
    }

    @Test
    void highspeedUsesTheChinaEndpointAndReportsItemErrors() {
        config.setMinimaxRegion("cn");
        server.enqueue(new MockResponse().setResponseCode(422).setBody("""
                {"data":[{"error":{"code":"1027","message":"Image rejected"}}]}
                """));
        var result = generate("MiniMax-H3-Image-Highspeed", 1);
        assertFalse(result.isSubmitted());
        assertTrue(result.getMessage().contains("Image rejected"), result.getMessage());
        assertEquals("https://api.minimax.cn/v1/images/generations", endpoint);
    }

    @Test
    void acceptsAnInlineH3Image() throws IOException {
        server.enqueue(new MockResponse().setBody("""
                {"data":[{"b64_json":"YWJj"}],"output_format":"png"}
                """));
        var result = generate("MiniMax-H3-Image", 1);
        assertTrue(result.isCompleted(), result.getMessage());
        assertEquals("abc", Files.readString(images.resolve(Path.of(result.getImageUrls().getFirst()).getFileName())));
    }

    @Test
    void reportsRequestErrors() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("""
                {"error":{"type":"rate_limit_error","message":"Rate limit reached","http_code":"429"}}
                """));
        var result = generate("MiniMax-H3-Image", 1);
        assertFalse(result.isSubmitted());
        assertTrue(result.getMessage().contains("Rate limit reached"), result.getMessage());
    }

    @Test
    void aDroppedResponseDoesNotReplayTheGenerationRequest() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        server.enqueue(new MockResponse().setResponseCode(500));
        assertFalse(generate("MiniMax-H3-Image", 1).isSubmitted());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void legacyDefaultKeepsItsExistingEndpointAndEnvelope() throws Exception {
        config.setMinimaxRegion("cn");
        server.enqueue(new MockResponse().setBody("""
                {"base_resp":{"status_code":0},"data":{"image_base64":["YWJj"]}}
                """));
        var result = generate(null, 1);
        assertTrue(result.isCompleted(), result.getMessage());
        assertEquals("https://api.minimaxi.com/v1/image_generation", endpoint);
        assertEquals("image-01", mapper.readTree(server.takeRequest().getBody().readUtf8()).path("model").asText());
        assertEquals("abc", Files.readString(images.resolve(Path.of(result.getImageUrls().getFirst()).getFileName())));
    }
}
