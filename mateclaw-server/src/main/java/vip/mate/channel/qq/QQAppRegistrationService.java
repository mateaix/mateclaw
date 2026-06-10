package vip.mate.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QQ Bot "scan-to-bind" registration service.
 * <p>
 * Drives the QQ Open Platform Lite bind portal:
 * <pre>
 *   POST {portal}/lite/create_bind_task   body {key}        → {task_id}
 *   POST {portal}/lite/poll_bind_result   body {task_id}    → {status, bot_appid?, bot_encrypt_secret?, user_openid?}
 * </pre>
 * <p>
 * The {@code key} is a base64-encoded 256-bit random AES key generated locally
 * — the portal uses it to AES-256-GCM-encrypt {@code client_secret} so the
 * plaintext never travels in the clear. Decryption happens here, after which
 * the session exposes {@code clientId} / {@code clientSecret} to the SPI
 * provider.
 * <p>
 * Sessions live in memory (ConcurrentHashMap) with a 12-minute TTL — the
 * QR code itself expires after ~5 min on the portal side, the extra buffer
 * is for late polls. A background worker polls the portal every 2s until
 * a terminal state, capped at 6 min wall-clock to avoid thread leaks.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QQAppRegistrationService {

    /** QQ Open Platform portal host (overridable for proxies / test envs). */
    private static final String PORTAL_HOST =
            System.getenv().getOrDefault("QQ_BIND_PORTAL_HOST", "q.qq.com");
    /** Vendor source tag forwarded to the portal in the QR URL. */
    private static final String PORTAL_SOURCE = "mateclaw";
    /** Portal path that hosts the user-facing scan landing page. */
    private static final String PORTAL_CONNECT_PATH = "/qqbot/openclaw/connect.html";

    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final long POLL_REQUEST_TIMEOUT_MS = 10_000L;
    private static final long INIT_REQUEST_TIMEOUT_MS = 15_000L;
    private static final long SESSION_TTL_MS = 12 * 60_000L;
    private static final long WORKER_MAX_RUNTIME_MS = 6 * 60_000L;

    /** Portal status codes (bind portal returns numeric codes, not strings). */
    private static final int PORTAL_STATUS_PENDING = 1;
    private static final int PORTAL_STATUS_COMPLETED = 2;
    private static final int PORTAL_STATUS_EXPIRED = 3;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ConcurrentHashMap<String, RegistrationSession> sessions = new ConcurrentHashMap<>();

    /**
     * Kick off a new bind session. Returns immediately with the QR URL set;
     * polling for completion happens in a background worker.
     */
    public RegistrationSession begin() throws Exception {
        evictExpiredSessions();

        String aesKey = QQBindCrypto.generateKey();
        Map<?, ?> response = postJson("/lite/create_bind_task", Map.of("key", aesKey), INIT_REQUEST_TIMEOUT_MS);
        Integer retcode = response.get("retcode") instanceof Number n ? n.intValue() : null;
        if (retcode == null || retcode != 0) {
            throw new IllegalStateException(
                    "create_bind_task failed: retcode=" + retcode + ", msg=" + response.get("msg"));
        }
        Object dataObj = response.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            throw new IllegalStateException("create_bind_task returned no data");
        }
        String taskId = data.get("task_id") instanceof String s ? s : null;
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("create_bind_task returned empty task_id");
        }

        String sessionId = UUID.randomUUID().toString();
        RegistrationSession session = new RegistrationSession(sessionId);
        session.qrcodeUrl = buildConnectUrl(taskId);
        session.status = Status.WAITING;
        sessions.put(sessionId, session);

        Thread worker = new Thread(() -> pollUntilTerminal(session, taskId, aesKey),
                "qq-register-" + sessionId.substring(0, 8));
        worker.setDaemon(true);
        worker.start();

        log.info("[qq-register] session {} started (task_id suffix=...{})",
                sessionId, taskId.length() > 6 ? taskId.substring(taskId.length() - 6) : taskId);
        return session;
    }

    public RegistrationSession getSession(String sessionId) {
        evictExpiredSessions();
        return sessions.get(sessionId);
    }

    private void pollUntilTerminal(RegistrationSession session, String taskId, String aesKey) {
        long startMs = System.currentTimeMillis();
        while (true) {
            if (System.currentTimeMillis() - startMs > WORKER_MAX_RUNTIME_MS) {
                session.status = Status.EXPIRED;
                session.errorMessage = "polling worker timed out";
                session.lastUpdateMs = System.currentTimeMillis();
                log.warn("[qq-register] session {} timed out after {} ms",
                        session.sessionId, WORKER_MAX_RUNTIME_MS);
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Map<?, ?> response = postJson("/lite/poll_bind_result",
                        Map.of("task_id", taskId), POLL_REQUEST_TIMEOUT_MS);
                Integer retcode = response.get("retcode") instanceof Number n ? n.intValue() : null;
                if (retcode == null || retcode != 0) {
                    log.debug("[qq-register] poll non-zero retcode={}, msg={} (will retry)",
                            retcode, response.get("msg"));
                    continue;
                }
                Object dataObj = response.get("data");
                if (!(dataObj instanceof Map<?, ?> data)) {
                    continue;
                }
                int portalStatus = data.get("status") instanceof Number n ? n.intValue() : 0;
                session.lastUpdateMs = System.currentTimeMillis();

                switch (portalStatus) {
                    case PORTAL_STATUS_COMPLETED -> {
                        String appId = data.get("bot_appid") instanceof String s ? s
                                : (data.get("bot_appid") != null ? data.get("bot_appid").toString() : null);
                        String encryptedSecret = data.get("bot_encrypt_secret") instanceof String s ? s : null;
                        String userOpenid = data.get("user_openid") instanceof String s ? s : null;
                        if (appId == null || encryptedSecret == null) {
                            session.status = Status.DENIED;
                            session.errorMessage = "portal returned completed without credentials";
                            log.warn("[qq-register] session {} completed but missing credentials", session.sessionId);
                            return;
                        }
                        try {
                            session.clientSecret = QQBindCrypto.decryptSecret(encryptedSecret, aesKey);
                        } catch (Exception e) {
                            session.status = Status.DENIED;
                            session.errorMessage = "failed to decrypt client_secret: " + e.getMessage();
                            log.error("[qq-register] session {} decrypt failed: {}",
                                    session.sessionId, e.getMessage());
                            return;
                        }
                        session.clientId = appId;
                        session.userOpenid = userOpenid;
                        session.status = Status.CONFIRMED;
                        log.info("[qq-register] session {} confirmed, appId={}", session.sessionId, appId);
                        return;
                    }
                    case PORTAL_STATUS_EXPIRED -> {
                        session.status = Status.EXPIRED;
                        log.info("[qq-register] session {} expired", session.sessionId);
                        return;
                    }
                    case PORTAL_STATUS_PENDING -> {
                        // keep polling
                    }
                    default -> log.debug("[qq-register] session {} unknown portal status: {}",
                            session.sessionId, portalStatus);
                }
            } catch (Exception e) {
                log.debug("[qq-register] poll attempt failed (will retry): {}", e.getMessage());
            }
        }
    }

    private String buildConnectUrl(String taskId) {
        String encoded = URLEncoder.encode(taskId, StandardCharsets.UTF_8);
        return "https://" + PORTAL_HOST + PORTAL_CONNECT_PATH
                + "?task_id=" + encoded + "&_wv=2&source=" + PORTAL_SOURCE;
    }

    private Map<?, ?> postJson(String path, Map<String, ?> body, long timeoutMs) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + PORTAL_HOST + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("portal " + path + " HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    private void evictExpiredSessions() {
        long cutoff = System.currentTimeMillis() - SESSION_TTL_MS;
        Iterator<Map.Entry<String, RegistrationSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().createdAtMs < cutoff) it.remove();
        }
    }

    public enum Status {
        WAITING, CONFIRMED, EXPIRED, DENIED
    }

    public static class RegistrationSession {
        public final String sessionId;
        final long createdAtMs = System.currentTimeMillis();

        public volatile Status status = Status.WAITING;
        public volatile String qrcodeUrl;
        public volatile String qrcodeImgDataUri;
        /** Decrypted bot app_id (filled on confirmed). */
        public volatile String clientId;
        /** Decrypted bot client_secret (filled on confirmed). */
        public volatile String clientSecret;
        /** OpenID of the user who scanned (filled on confirmed). */
        public volatile String userOpenid;
        public volatile String errorMessage;
        public volatile long lastUpdateMs = System.currentTimeMillis();

        RegistrationSession(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
