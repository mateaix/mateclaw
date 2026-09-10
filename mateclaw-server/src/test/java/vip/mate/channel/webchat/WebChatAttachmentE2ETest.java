package vip.mate.channel.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import vip.mate.MateClawApplication;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * End-to-end HTTP coverage of the WebChat attachment flow (epic #355 follow-up):
 * <ol>
 *   <li>{@code POST /upload} stores bytes under the server-derived conversation dir
 *       and returns an opaque {@code fileId}; the bytes are addressable by the
 *       agent through the conversation's upload path;</li>
 *   <li>{@code POST /stream} with {@code attachmentIds=[fileId]} resolves that id
 *       back to a server path and persists a user message whose {@code content_parts}
 *       carry the path so the agent's file tools can read it on the next turn.</li>
 * </ol>
 *
 * <p>Boots a real servlet container (RANDOM_PORT), drives the actual multipart
 * parser, and asserts on the persisted {@code mate_message.content_parts} JSON —
 * the in-memory {@link WebChatFileServiceTest} already covers the service's
 * validation, so this layer's job is the cross-endpoint wiring + auth contract.
 *
 * <p>{@link AgentService} is mocked (same pattern as {@link WebChatStreamE2ETest})
 * so /stream returns immediately without invoking a real agent — what we are
 * asserting is the persisted user-message shape, not the agent's actual file
 * consumption (which would need a real agent + tool runtime).
 *
 * @author MateClaw Team
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_att_e2e_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789",
        "mateclaw.feature-flag.refresh-ms=999999"
})
class WebChatAttachmentE2ETest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1atte2e01"; // key8 = "testkey1"
    private static final long CHANNEL_ID = 9_148_101L;
    private static final long AGENT_ID = 9_148_1011L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Wipe the upload dir for our test conversation ids. The path is deterministic
     * from the API key prefix, so we can clean it precisely instead of nuking
     * the whole {@code data/chat-uploads} tree.
     */
    private static final Path UPLOAD_ROOT = Paths.get("data", "chat-uploads");

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private AgentService agentService;

    private HttpClient http;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-att-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc', 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, AGENT_ID, "{\"api_key\":\"" + API_KEY + "\"}");

        // chatStructuredStream: instant completion so /stream returns fast.
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setWorkspaceId(1L);
        org.mockito.Mockito.when(agentService.getAgent(AGENT_ID)).thenReturn(agent);
        org.mockito.Mockito.when(agentService.chatStructuredStream(
                        eq(AGENT_ID), anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(Flux.just(new AgentService.StreamDelta("ack", null)));
    }

    // ==================== multipart helpers ====================

    /** Build a multipart/form-data body with one file part plus arbitrary form fields. */
    private static byte[] multipart(String boundary, Map<String, String> fields,
                                    String fileField, String fileName, String contentType, byte[] fileBytes) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String crlf = "\r\n";
        String dash = "--";
        try {
            for (var e : fields.entrySet()) {
                write(bos, dash + boundary + crlf);
                write(bos, "Content-Disposition: form-data; name=\"" + e.getKey() + "\"" + crlf);
                write(bos, crlf);
                write(bos, e.getValue() + crlf);
            }
            write(bos, dash + boundary + crlf);
            write(bos, "Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                    + fileName + "\"" + crlf);
            write(bos, "Content-Type: " + contentType + crlf);
            write(bos, crlf);
            bos.write(fileBytes);
            write(bos, crlf);
            write(bos, dash + boundary + dash + crlf);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    private static void write(ByteArrayOutputStream bos, String s) throws IOException {
        bos.write(s.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== HTTP helpers ====================

    private URI uploadUri() {
        return URI.create("http://localhost:" + port + "/api/v1/channels/webchat/upload");
    }

    private URI streamUri() {
        return URI.create("http://localhost:" + port + "/api/v1/channels/webchat/stream");
    }

    private URI filesUri(String storedName, String visitorId, String sessionId) {
        StringBuilder sb = new StringBuilder("http://localhost:" + port + "/api/v1/channels/webchat/files");
        sb.append("?storedName=").append(java.net.URLEncoder.encode(storedName, StandardCharsets.UTF_8));
        sb.append("&visitorId=").append(java.net.URLEncoder.encode(visitorId, StandardCharsets.UTF_8));
        if (sessionId != null) {
            sb.append("&sessionId=").append(java.net.URLEncoder.encode(sessionId, StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }

    private String tokenFor(String visitorId) {
        return WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, visitorId);
    }

    /** Upload, return the parsed fileId (or fail loudly if the response isn't 200). */
    private String upload(String visitorId, String sessionId, String fileName,
                          String contentType, byte[] bytes) throws Exception {
        String boundary = "----mcboundary" + System.nanoTime();
        String token = tokenFor(visitorId);
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("visitorId", visitorId);
        if (sessionId != null) fields.put("sessionId", sessionId);
        byte[] body = multipart(boundary, fields, "file", fileName, contentType, bytes);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uploadUri())
                .timeout(HTTP_TIMEOUT)
                .header("X-MC-Key", API_KEY)
                .header("X-MC-Visitor-Token", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).as("upload response: %s", resp.body()).isEqualTo(200);
        // Response shape: {"code":200,...,"data":{"fileId":"...","fileName":"...",...}}
        String fileId = extractStringField(resp.body(), "fileId");
        assertThat(fileId).isNotBlank();
        return fileId;
    }

    /** POST /stream with the given message + attachmentIds; drain SSE until done. */
    private void stream(String visitorId, String sessionId, String message, String attachmentJson) throws Exception {
        String attachmentField = attachmentJson == null ? "" : ",\"attachmentIds\":" + attachmentJson;
        String sessionField = sessionId == null ? "" : ",\"sessionId\":\"" + sessionId + "\"";
        String body = "{\"message\":\"" + message + "\",\"visitorId\":\"" + visitorId + "\""
                + sessionField + attachmentField + "}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(streamUri())
                .timeout(HTTP_TIMEOUT)
                .header("X-MC-Key", API_KEY)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        // Drain to done so the request completes within the test.
        try (var is = resp.body();
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:done")) break;
            }
        }
    }

    /** Pull the persisted user message's raw content_parts JSON for this conversation. */
    private String lastUserContentParts(String conversationId) {
        return jdbc.queryForObject(
                "SELECT content_parts FROM mate_message WHERE conversation_id = ? AND role = 'user' " +
                        "ORDER BY create_time DESC, id DESC LIMIT 1",
                String.class, conversationId);
    }

    /** Naive JSON string-field extractor — avoids pulling in Jackson in the test body. */
    private static String extractStringField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    // ==================== cleanup ====================

    @org.junit.jupiter.api.AfterEach
    void cleanUploadDirs() throws IOException {
        // Only the convs under our key8 prefix are ours; safe to wipe.
        Path root = UPLOAD_ROOT.resolve("webchat:testkey1");
        if (Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    // ==================== tests ====================

    @Test
    @DisplayName("upload + /stream round-trip: user message content_parts carries the file path")
    void uploadThenStreamAttachesPath() throws Exception {
        String visitorId = "vAtt-roundtrip";
        String sessionId = "s-att-1";
        String fileBody = "hello attachment e2e";
        String fileId = upload(visitorId, sessionId, "note.txt", "text/plain", fileBody.getBytes(StandardCharsets.UTF_8));

        stream(visitorId, sessionId, "please read the attached", "[\"" + fileId + "\"]");

        String cid = WebChatController.deriveConversationId(CHANNEL_ID, visitorId, sessionId);
        String parts = lastUserContentParts(cid);
        assertThat(parts).isNotNull();
        // Text part is present.
        assertThat(parts).contains("\"type\":\"text\"");
        assertThat(parts).contains("please read the attached");
        // File part is present with the server path resolved.
        assertThat(parts).contains("\"type\":\"file\"");
        assertThat(parts).contains("\"fileName\":\"note.txt\"");
        assertThat(parts).contains("\"contentType\":\"text/plain\"");
        assertThat(parts).contains("\"path\":\"");
        // Path points into the conversation's upload dir on disk. The dir uses
        // the sanitized conversation id (cid carries ':' which is path-illegal
        // on Windows), so assert against the sanitized segment.
        String path = extractStringField(parts, "path");
        assertThat(path).contains(ChatUploadLocationResolver.sanitizeSegment(cid));
        assertThat(Files.isRegularFile(Path.of(path))).isTrue();
        // The bytes on disk match what we uploaded.
        assertThat(Files.readString(Path.of(path))).isEqualTo(fileBody);
    }

    @Test
    @DisplayName("unknown attachmentId is silently dropped — no error, user message has text only")
    void unknownAttachmentIdDropped() throws Exception {
        String visitorId = "vAtt-unknown";
        stream(visitorId, null, "hello", "[\"totally-bogus-file-id\"]");

        String cid = WebChatController.deriveConversationId(CHANNEL_ID, visitorId, null);
        String parts = lastUserContentParts(cid);
        assertThat(parts).isNotNull();
        assertThat(parts).contains("\"type\":\"text\"");
        // No file part at all.
        assertThat(parts).doesNotContain("\"type\":\"file\"");
    }

    @Test
    @DisplayName("foreign visitor cannot reference another visitor's attachmentId")
    void foreignAttachmentIdDropped() throws Exception {
        // visitor A uploads legitimately.
        String visitorA = "vAtt-alice";
        String visitorB = "vAtt-bob";
        String aliceFileId = upload(visitorA, null, "alice.txt", "text/plain",
                "alice's secret".getBytes(StandardCharsets.UTF_8));

        // visitor B references alice's fileId — should be silently dropped.
        stream(visitorB, null, "trying to grab alice's file", "[\"" + aliceFileId + "\"]");

        // B's conversation's user message has no file part.
        String bobCid = WebChatController.deriveConversationId(CHANNEL_ID, visitorB, null);
        String bobParts = lastUserContentParts(bobCid);
        assertThat(bobParts).doesNotContain("\"type\":\"file\"");
        // Alice's conversation is untouched — no user message there at all.
        String aliceCid = WebChatController.deriveConversationId(CHANNEL_ID, visitorA, null);
        Integer aliceMsgCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_message WHERE conversation_id = ? AND role = 'user'",
                Integer.class, aliceCid);
        assertThat(aliceMsgCount).isZero();
    }

    @Test
    @DisplayName("upload without visitorToken → HTTP 401 (RHttpStatusAdvice maps R.code to status)")
    void uploadRequiresVisitorToken() throws Exception {
        String boundary = "----mcboundary" + System.nanoTime();
        byte[] body = multipart(boundary, Map.of("visitorId", "vAtt-notoken"),
                "file", "x.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uploadUri())
                .timeout(HTTP_TIMEOUT)
                .header("X-MC-Key", API_KEY)
                // No X-MC-Visitor-Token
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(resp.body()).contains("\"code\":401");
        assertThat(resp.body()).contains("Invalid or missing visitor token");
    }

    @Test
    @DisplayName("upload with disallowed extension → HTTP 400")
    void uploadRejectsDisallowedExtension() throws Exception {
        String boundary = "----mcboundary" + System.nanoTime();
        byte[] body = multipart(boundary, Map.of("visitorId", "vAtt-badext"),
                "file", "evil.exe", "application/octet-stream", new byte[]{1, 2, 3});
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uploadUri())
                .timeout(HTTP_TIMEOUT)
                .header("X-MC-Key", API_KEY)
                .header("X-MC-Visitor-Token", tokenFor("vAtt-badext"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("File type not allowed");
    }

    @Test
    @DisplayName("GET /files streams back the uploaded bytes")
    void downloadReturnsUploadedBytes() throws Exception {
        String visitorId = "vAtt-download";
        String sessionId = "s-dl-1";
        byte[] payload = "download me".getBytes(StandardCharsets.UTF_8);
        String fileId = upload(visitorId, sessionId, "payload.txt", "text/plain", payload);

        // /stream must consume the staged file first; download's ownsConversation
        // guard also needs the conversation row to exist. sessionId MUST be
        // threaded through /files too — the controller recomputes conversationId
        // from (apiKey, visitorId, sessionId) so a missing sid maps to a
        // different namespace than where the upload lives.
        stream(visitorId, sessionId, "first message", "[\"" + fileId + "\"]");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(filesUri(fileId, visitorId, sessionId))
                .timeout(HTTP_TIMEOUT)
                .header("X-MC-Key", API_KEY)
                .header("X-MC-Visitor-Token", tokenFor(visitorId))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo(payload);
        assertThat(resp.headers().firstValue("Content-Type").orElse(""))
                .contains("text/plain");
    }

    @Test
    @DisplayName("GET /files without visitorToken → 401")
    void downloadRequiresVisitorToken() throws Exception {
        String visitorId = "vAtt-dl-notoken";
        String sessionId = "s-dl-2";
        String fileId = upload(visitorId, sessionId, "x.txt", "text/plain",
                "x".getBytes(StandardCharsets.UTF_8));
        stream(visitorId, sessionId, "first", "[\"" + fileId + "\"]");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(filesUri(fileId, visitorId, sessionId))
                .timeout(HTTP_TIMEOUT)
                .header("X-MC-Key", API_KEY)
                // No X-MC-Visitor-Token
                .GET()
                .build();
        HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        assertThat(resp.statusCode()).isEqualTo(401);
    }
}
