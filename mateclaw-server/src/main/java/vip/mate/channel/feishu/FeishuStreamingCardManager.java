package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.CreateCardResp;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardResp;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streaming-card lifecycle for the Feishu CardKit v1 API.
 *
 * <p>Flow per stream:
 * <ol>
 *   <li>{@link #createAndDeliver} — build a {@code streaming_mode=true}
 *       schema-2.0 card via {@code cardkit/v1/card.create}, then send
 *       it to the user via {@code im/v1/message.create} as an
 *       {@code interactive} message referencing the new {@code card_id}.
 *       Returns a session key used by subsequent calls.</li>
 *   <li>{@link #appendContent} — accumulate delta text and, when the
 *       throttle window permits or the caller forces a flush, push the
 *       current accumulator to the card's markdown element via
 *       {@code cardkit/v1/cardElement.content} with a monotonic
 *       sequence number.</li>
 *   <li>{@link #finishCard} — push the final full content one last
 *       time, then turn off {@code streaming_mode} via
 *       {@code cardkit/v1/card.settings} so the receiver UI stops
 *       showing the typing animation.</li>
 *   <li>{@link #failCard} — append an error marker to whatever was
 *       accumulated, then close streaming the same way.</li>
 * </ol>
 *
 * <p>Designed mirror-image to {@code DingTalkAICardManager}: same
 * create/append/finish/fail shape, same per-session throttling,
 * same activeSessions map for hand-off between threads. The
 * implementation is end-to-end {@code oapi-sdk} — no hand-rolled HTTP.
 *
 * <p>The four SDK call sites are {@code protected} so unit tests can
 * subclass and verify session/throttle behavior without booting a real
 * Feishu credential or hitting the network.
 */
@Slf4j
@Component
public class FeishuStreamingCardManager {

    /** Throttle window for {@link #appendContent}, ms — matches DingTalk AICard. */
    static final long THROTTLE_INTERVAL_MS = 500;

    /**
     * Markdown element id baked into the initial streaming card.
     * Content-update calls reference this id. Public so tests can assert.
     */
    public static final String STREAM_ELEMENT_ID = "stream_md";

    /** Default text shown when the card is first created, before any delta arrives. */
    public static final String DEFAULT_INITIAL_TEXT = "🤔 思考中...";

    private final FeishuClientFactory clientFactory;
    private final ObjectMapper objectMapper;

    /** sessionKey → CardSession. sessionKey is an opaque UUID handed back to the caller. */
    private final ConcurrentHashMap<String, CardSession> activeSessions = new ConcurrentHashMap<>();

    public FeishuStreamingCardManager(FeishuClientFactory clientFactory, ObjectMapper objectMapper) {
        this.clientFactory = clientFactory;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Session state
    // ------------------------------------------------------------------

    /** Terminal-state CAS guard — at most one of {finishCard, failCard} wins per session. */
    enum Status { STREAMING, FINISHED, FAILED }

    /**
     * One in-flight streaming card. State is mutated by a single Reactor
     * thread per session (the one consuming the {@code Flux}), so all
     * mutable fields are either {@code volatile} (visibility across the
     * eventual terminal call) or guarded by the session monitor.
     */
    static final class CardSession {
        final String sessionKey;
        final Long channelId;
        final String cardId;
        final String messageId;
        final StringBuilder accumulated = new StringBuilder();
        final AtomicInteger sequence = new AtomicInteger(0);
        final AtomicReference<Status> status = new AtomicReference<>(Status.STREAMING);
        /**
         * Time of the most recent flush. Initialised to a value well in
         * the past so the very first {@link #appendContent} always
         * flushes — the receiver sees the first token instantly instead
         * of waiting up to {@link #THROTTLE_INTERVAL_MS} for the second.
         */
        volatile long lastFlushMs = Long.MIN_VALUE / 2;

        CardSession(String sessionKey, Long channelId, String cardId, String messageId) {
            this.sessionKey = sessionKey;
            this.channelId = channelId;
            this.cardId = cardId;
            this.messageId = messageId;
        }

        boolean isStreaming() {
            return status.get() == Status.STREAMING;
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Build the streaming card, push it as an interactive message, and
     * register an in-memory session.
     *
     * @param channelId      mate_channel row id — picks SDK client
     * @param receiveIdType  one of {@code open_id} / {@code chat_id} /
     *                       {@code email} / {@code union_id} /
     *                       {@code user_id}
     * @param receiveId      the chat or user id to receive the card
     * @param initialText    bubble text shown before the first delta;
     *                       null → {@link #DEFAULT_INITIAL_TEXT}
     * @return sessionKey for subsequent calls, or null on failure
     */
    public String createAndDeliver(Long channelId, String receiveIdType,
                                    String receiveId, String initialText) {
        if (channelId == null || receiveIdType == null || receiveId == null) {
            log.warn("[feishu-stream] createAndDeliver missing required arg(s)");
            return null;
        }
        String firstText = (initialText == null || initialText.isBlank())
                ? DEFAULT_INITIAL_TEXT
                : initialText;
        try {
            Client client = clientFactory.client(channelId);

            String cardId = sdkCreateCard(client, firstText);
            if (cardId == null) {
                return null;
            }
            String messageId = sdkSendInteractiveMessage(client, receiveIdType, receiveId, cardId);
            if (messageId == null) {
                // Card built but couldn't deliver — best effort close so the
                // server-side card isn't orphaned in streaming mode forever.
                tryCloseStreamingSilently(client, cardId);
                return null;
            }
            String sessionKey = UUID.randomUUID().toString();
            CardSession session = new CardSession(sessionKey, channelId, cardId, messageId);
            activeSessions.put(sessionKey, session);
            log.info("[feishu-stream] Card created: sessionKey={}, cardId={}, messageId={}",
                    sessionKey, abbrev(cardId), abbrev(messageId));
            return sessionKey;
        } catch (Exception e) {
            log.error("[feishu-stream] createAndDeliver failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Append delta text to the running session. May flush immediately
     * (force) or wait for the next throttle window.
     *
     * <p>No-op when {@code sessionKey} is unknown or the session has
     * already reached a terminal status — keeps the caller's
     * {@code doOnNext} loop simple ("just push every chunk").
     */
    public void appendContent(String sessionKey, String contentDelta, boolean forceFlush) {
        CardSession session = activeSessions.get(sessionKey);
        if (session == null || !session.isStreaming()) {
            return;
        }
        if (contentDelta != null && !contentDelta.isEmpty()) {
            synchronized (session) {
                session.accumulated.append(contentDelta);
            }
        }
        long now = currentTimeMs();
        if (!forceFlush && now - session.lastFlushMs < THROTTLE_INTERVAL_MS) {
            return;
        }
        flush(session, now);
    }

    /**
     * Push final content and turn off streaming mode. Idempotent —
     * a second call is a no-op. After return, the sessionKey is no
     * longer known to the manager.
     */
    public void finishCard(String sessionKey, String finalContent) {
        CardSession session = activeSessions.get(sessionKey);
        if (session == null) return;
        if (!session.status.compareAndSet(Status.STREAMING, Status.FINISHED)) {
            return;
        }
        try {
            replaceAccumulated(session, finalContent != null ? finalContent : "");
            flush(session, currentTimeMs());
            closeStreaming(session);
        } finally {
            activeSessions.remove(sessionKey);
            log.info("[feishu-stream] Card finished: sessionKey={}, contentLen={}",
                    sessionKey, finalContent == null ? 0 : finalContent.length());
        }
    }

    /**
     * Mark the session failed. The current accumulator gets an error
     * suffix; the card is closed so the typing animation stops.
     * Idempotent.
     */
    public void failCard(String sessionKey, String errorMessage) {
        CardSession session = activeSessions.get(sessionKey);
        if (session == null) return;
        if (!session.status.compareAndSet(Status.STREAMING, Status.FAILED)) {
            return;
        }
        try {
            String tail;
            synchronized (session) {
                if (session.accumulated.length() == 0) {
                    tail = "⚠️ 处理失败：" + safe(errorMessage);
                } else {
                    tail = session.accumulated + "\n\n⚠️ " + safe(errorMessage);
                }
                session.accumulated.setLength(0);
                session.accumulated.append(tail);
            }
            flush(session, currentTimeMs());
            closeStreaming(session);
        } finally {
            activeSessions.remove(sessionKey);
            log.warn("[feishu-stream] Card failed: sessionKey={}, error={}", sessionKey, errorMessage);
        }
    }

    // ------------------------------------------------------------------
    // Inspection helpers (tests / metrics)
    // ------------------------------------------------------------------

    /** Visible for tests / metrics — number of in-flight sessions. */
    public int activeSessionCount() {
        return activeSessions.size();
    }

    /** Visible for tests — direct session lookup. */
    CardSession sessionFor(String sessionKey) {
        return activeSessions.get(sessionKey);
    }

    // ------------------------------------------------------------------
    // Internal — flush + SDK seams
    // ------------------------------------------------------------------

    private void flush(CardSession session, long now) {
        String snapshot;
        synchronized (session) {
            snapshot = session.accumulated.toString();
        }
        int seq = session.sequence.incrementAndGet();
        try {
            Client client = clientFactory.client(session.channelId);
            sdkPushElementContent(client, session.cardId, STREAM_ELEMENT_ID, snapshot, seq);
            session.lastFlushMs = now;
        } catch (Exception e) {
            log.warn("[feishu-stream] flush failed: sessionKey={}, seq={}, err={}",
                    session.sessionKey, seq, e.getMessage());
        }
    }

    private void closeStreaming(CardSession session) {
        int seq = session.sequence.incrementAndGet();
        try {
            Client client = clientFactory.client(session.channelId);
            sdkCloseStreamingMode(client, session.cardId, seq);
        } catch (Exception e) {
            log.warn("[feishu-stream] closeStreaming failed: sessionKey={}, err={}",
                    session.sessionKey, e.getMessage());
        }
    }

    private void tryCloseStreamingSilently(Client client, String cardId) {
        try {
            sdkCloseStreamingMode(client, cardId, 1);
        } catch (Exception ignore) {
            // best-effort — already in an error path
        }
    }

    private void replaceAccumulated(CardSession session, String content) {
        synchronized (session) {
            session.accumulated.setLength(0);
            session.accumulated.append(content);
        }
    }

    // ------------------------------------------------------------------
    // SDK seams (overridable in tests)
    // ------------------------------------------------------------------

    /** Build a streaming-mode schema-2.0 card. Returns the new card_id or null. */
    protected String sdkCreateCard(Client client, String initialText) throws Exception {
        String cardJson = objectMapper.writeValueAsString(buildInitialCardJson(initialText));
        CreateCardReq req = CreateCardReq.newBuilder()
                .createCardReqBody(CreateCardReqBody.newBuilder()
                        .type("card_json")
                        .data(cardJson)
                        .build())
                .build();
        CreateCardResp resp = client.cardkit().v1().card().create(req);
        if (!resp.success() || resp.getData() == null) {
            log.warn("[feishu-stream] card.create failed: code={}, msg={}", resp.getCode(), resp.getMsg());
            return null;
        }
        return resp.getData().getCardId();
    }

    /** Send the freshly-built card as an interactive message. Returns message_id or null. */
    protected String sdkSendInteractiveMessage(Client client, String receiveIdType,
                                                String receiveId, String cardId) throws Exception {
        Map<String, Object> content = Map.of(
                "type", "card",
                "data", Map.of("card_id", cardId)
        );
        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType(receiveIdType)
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(receiveId)
                        .msgType("interactive")
                        .content(objectMapper.writeValueAsString(content))
                        .build())
                .build();
        CreateMessageResp resp = client.im().v1().message().create(req);
        if (!resp.success() || resp.getData() == null) {
            log.warn("[feishu-stream] interactive message send failed: code={}, msg={}",
                    resp.getCode(), resp.getMsg());
            return null;
        }
        return resp.getData().getMessageId();
    }

    /** Push the latest accumulator snapshot to the streaming element. */
    protected void sdkPushElementContent(Client client, String cardId, String elementId,
                                          String content, int sequence) throws Exception {
        ContentCardElementReq req = ContentCardElementReq.newBuilder()
                .cardId(cardId)
                .elementId(elementId)
                .contentCardElementReqBody(ContentCardElementReqBody.newBuilder()
                        .content(content)
                        .uuid(UUID.randomUUID().toString())
                        .sequence(sequence)
                        .build())
                .build();
        ContentCardElementResp resp = client.cardkit().v1().cardElement().content(req);
        if (!resp.success()) {
            log.warn("[feishu-stream] cardElement.content failed: cardId={}, seq={}, code={}, msg={}",
                    abbrev(cardId), sequence, resp.getCode(), resp.getMsg());
        }
    }

    /** Flip streaming_mode=false so the receiving UI stops the typing animation. */
    protected void sdkCloseStreamingMode(Client client, String cardId, int sequence) throws Exception {
        Map<String, Object> settings = Map.of(
                "config", Map.of("streaming_mode", false)
        );
        SettingsCardReq req = SettingsCardReq.newBuilder()
                .cardId(cardId)
                .settingsCardReqBody(SettingsCardReqBody.newBuilder()
                        .settings(objectMapper.writeValueAsString(settings))
                        .uuid(UUID.randomUUID().toString())
                        .sequence(sequence)
                        .build())
                .build();
        SettingsCardResp resp = client.cardkit().v1().card().settings(req);
        if (!resp.success()) {
            log.warn("[feishu-stream] card.settings (close) failed: cardId={}, code={}, msg={}",
                    abbrev(cardId), resp.getCode(), resp.getMsg());
        }
    }

    // ------------------------------------------------------------------
    // Test seams + tiny helpers
    // ------------------------------------------------------------------

    /** Overridable so tests can pin time without involving Clock + reflection. */
    protected long currentTimeMs() {
        return System.currentTimeMillis();
    }

    /** Visible for tests. The "schema 2.0 streaming card" baseline. */
    Map<String, Object> buildInitialCardJson(String initialText) {
        // LinkedHashMap → deterministic JSON order, easier to log-grep
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("streaming_mode", true);
        config.put("update_multi", true);

        Map<String, Object> element = new LinkedHashMap<>();
        element.put("tag", "markdown");
        element.put("element_id", STREAM_ELEMENT_ID);
        element.put("content", initialText);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("elements", List.of(element));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", config);
        card.put("body", body);
        return card;
    }

    private static String abbrev(String s) {
        if (s == null || s.length() <= 12) return s == null ? "" : s;
        return s.substring(0, 12) + "…";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
