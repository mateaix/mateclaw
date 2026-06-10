package vip.mate.channel.dingtalk;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.StreamingChannelAdapter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 钉钉渠道适配器
 * <p>
 * 支持两种接入模式：
 * - <b>Stream 模式（推荐）</b>：WebSocket 长连接，无需公网 IP，钉钉官方推荐
 * - <b>Webhook 模式</b>：HTTP 回调，需要公网可访问的 URL
 * <p>
 * 消息格式：
 * - <b>markdown</b>：普通 Markdown 消息
 * - <b>card</b>：AI Card 流式卡片（需配置 card_template_id）
 * <p>
 * 配置项（configJson）：
 * - connection_mode: 接入模式（stream / webhook），默认 stream
 * - client_id: 钉钉应用 AppKey
 * - client_secret: 钉钉应用 AppSecret
 * - message_type: 消息格式（markdown / card），默认 markdown
 * - card_template_id: AI Card 模板 ID（message_type=card 时必填）
 * - robot_code: 机器人编码（card 模式群聊建议配置）
 *
 * @author MateClaw Team
 */
@Slf4j
public class DingTalkChannelAdapter extends AbstractChannelAdapter implements StreamingChannelAdapter {

    public static final String CHANNEL_TYPE = "dingtalk";

    private HttpClient httpClient;

    /** 钉钉 Stream 客户端（Stream 模式下使用） */
    private OpenDingTalkClient streamClient;

    /** AI Card 管理器（message_type=card 时初始化） */
    private DingTalkAICardManager aiCardManager;

    /** 钉钉媒体上传器（doStart 时初始化） */
    private DingTalkMediaUploader mediaUploader;

    /** 工具产生的可下载字节缓存（DocxRenderTool 等用，注入避免回调到自己的 HTTP API） */
    private final vip.mate.tool.document.GeneratedFileCache generatedFileCache;

    public DingTalkChannelAdapter(ChannelEntity channelEntity,
                                  ChannelMessageRouter messageRouter,
                                  ObjectMapper objectMapper,
                                  vip.mate.tool.document.GeneratedFileCache generatedFileCache) {
        super(channelEntity, messageRouter, objectMapper);
        this.generatedFileCache = generatedFileCache;
        // 钉钉 Stream 重连：2s→4s→8s→16s→30s，无限重试
        this.backoff = new ExponentialBackoff(2000, 30000, 2.0, -1);
    }

    /**
     * 获取接入模式：stream（默认，推荐） 或 webhook
     */
    public String getConnectionMode() {
        return getConfigString("connection_mode", "stream");
    }

    /**
     * 是否为 Stream 长连接模式
     */
    public boolean isStreamMode() {
        return "stream".equals(getConnectionMode());
    }

    @Override
    protected void doStart() {
        String clientId = getConfigString("client_id");
        String clientSecret = getConfigString("client_secret");

        if (clientId == null || clientSecret == null) {
            throw new IllegalStateException("DingTalk channel requires client_id and client_secret in configJson");
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 媒体上传器：sampleFile / sampleImageMsg 都靠它先把字节传到钉钉拿 mediaId
        this.mediaUploader = new DingTalkMediaUploader(httpClient, objectMapper);

        // 初始化 AI Card 管理器（message_type=card 且配置了模板 ID）
        String cardTemplateId = getConfigString("card_template_id");
        String messageType = getConfigString("message_type", "markdown");
        if ("card".equals(messageType) && cardTemplateId != null && !cardTemplateId.isBlank()) {
            this.aiCardManager = new DingTalkAICardManager(httpClient, objectMapper, clientId, clientSecret);
            log.info("[dingtalk] AI Card enabled: templateId={}", cardTemplateId);
        }

        // 启动 Stream 模式或 Webhook 模式
        if (isStreamMode()) {
            startStreamMode(clientId, clientSecret);
        } else {
            log.info("[dingtalk] Webhook mode: waiting for callbacks at /api/v1/channels/webhook/dingtalk");
        }

        log.info("[dingtalk] DingTalk channel initialized: mode={}, clientId={}, robotCode={}, aiCard={}",
                getConnectionMode(), clientId, getConfigString("robot_code"), isAICardEnabled());
    }

    /**
     * 启动 Stream 长连接模式
     * <p>
     * 使用钉钉 Stream SDK（dingtalk-stream）建立 WebSocket 长连接，
     * 通过 {@link OpenDingTalkCallbackListener} 回调接收机器人消息，无需公网 IP。
     * <p>
     * SDK 内部自带断线重连机制。
     */
    private void startStreamMode(String clientId, String clientSecret) {
        try {
            OpenDingTalkCallbackListener<ChatbotMessage, Void> botListener = message -> {
                try {
                    handleStreamMessage(message);
                } catch (Exception e) {
                    log.error("[dingtalk-stream] Failed to handle message: {}", e.getMessage(), e);
                }
                return null;
            };

            this.streamClient = OpenDingTalkStreamClientBuilder.custom()
                    .credential(new AuthClientCredential(clientId, clientSecret))
                    .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, botListener)
                    .build();
            streamClient.start();
            log.info("[dingtalk-stream] Stream connection established (no public IP needed)");
        } catch (Exception e) {
            log.error("[dingtalk-stream] Failed to start stream client: {}", e.getMessage(), e);
            throw new RuntimeException("DingTalk Stream start failed: " + e.getMessage(), e);
        }
    }

    /**
     * 处理 Stream 模式收到的机器人消息
     * <p>
     * 从 SDK 的 {@link ChatbotMessage} 提取字段，构建与 Webhook 兼容的 payload Map，
     * 复用 {@link #handleWebhook(Map)} 进行统一处理。
     */
    private void handleStreamMessage(ChatbotMessage msg) {
        try {
            // 构建与 Webhook payload 格式兼容的 Map，复用已有解析逻辑
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("msgId", msg.getMsgId());
            payload.put("senderStaffId", msg.getSenderStaffId());
            payload.put("senderId", msg.getSenderId());
            payload.put("senderNick", msg.getSenderNick());
            payload.put("conversationId", msg.getConversationId());
            payload.put("conversationType", msg.getConversationType());
            payload.put("sessionWebhook", msg.getSessionWebhook());

            // Dispatch by content type. DingTalk pre-transcribes voice into
            // MessageContent.recognition (same shape as WeCom voice.content),
            // so we never need to run STT ourselves.
            //   - audio (recognition)             -> forwarded as text
            //   - picture / downloadCode          -> webhook picture branch fetches bytes
            //   - richText (List<MessageContent>) -> rebuild the webhook richText payload
            //   - text                            -> text.content
            com.dingtalk.open.app.api.models.bot.MessageContent body = msg.getContent();
            String recognition = body != null ? body.getRecognition() : null;
            String pictureDownloadCode = body != null ? body.getPictureDownloadCode() : null;
            String downloadCode = body != null ? body.getDownloadCode() : null;
            java.util.List<com.dingtalk.open.app.api.models.bot.MessageContent> richTextItems =
                    body != null ? body.getRichText() : null;
            String msgtype = msg.getMsgtype();

            if (recognition != null && !recognition.isBlank()) {
                payload.put("msgtype", "audio");
                payload.put("audio", Map.of("recognition", recognition));
            } else if ("picture".equals(msgtype)
                    && (pictureDownloadCode != null || downloadCode != null)) {
                payload.put("msgtype", "picture");
                Map<String, Object> picture = new java.util.HashMap<>();
                if (pictureDownloadCode != null) picture.put("pictureDownloadCode", pictureDownloadCode);
                if (downloadCode != null) picture.put("downloadCode", downloadCode);
                payload.put("picture", picture);
            } else if ("richText".equalsIgnoreCase(msgtype)
                    || (richTextItems != null && !richTextItems.isEmpty())) {
                // richText: rebuild a webhook-compatible richText.richText array.
                // Without this branch, group @-mentions / formatted text /
                // quoted replies all fall through to the default webhook else
                // branch and get silently dropped — that's the "console
                // doesn't receive" symptom users hit on first DingTalk test.
                List<Map<String, Object>> items = new ArrayList<>();
                if (richTextItems != null) {
                    for (com.dingtalk.open.app.api.models.bot.MessageContent item : richTextItems) {
                        if (item == null) continue;
                        Map<String, Object> m = new java.util.HashMap<>();
                        if (item.getText() != null) m.put("text", item.getText());
                        if (item.getType() != null) m.put("type", item.getType());
                        if (item.getDownloadCode() != null) m.put("downloadCode", item.getDownloadCode());
                        if (item.getPictureDownloadCode() != null) {
                            m.put("pictureDownloadCode", item.getPictureDownloadCode());
                        }
                        if (!m.isEmpty()) items.add(m);
                    }
                }
                payload.put("msgtype", "richText");
                payload.put("richText", Map.of("richText", items));
            } else if (msg.getText() != null) {
                payload.put("msgtype", "text");
                payload.put("text", Map.of("content",
                        msg.getText().getContent() != null ? msg.getText().getContent() : ""));
            } else {
                log.warn("[dingtalk-stream] unsupported msgtype={}, msgId={}, dropping",
                        msgtype, msg.getMsgId());
                return;
            }

            handleWebhook(payload);
        } catch (Exception e) {
            log.error("[dingtalk-stream] Failed to parse stream message: {}", e.getMessage(), e);
        }
    }

    @Override
    protected void doStop() {
        // 关闭 Stream 客户端
        if (streamClient != null) {
            try {
                streamClient.stop();
                log.info("[dingtalk-stream] Stream client stopped");
            } catch (Exception e) {
                log.warn("[dingtalk-stream] Error stopping stream client: {}", e.getMessage());
            }
            streamClient = null;
        }
        if (aiCardManager != null) {
            aiCardManager.cleanup();
            aiCardManager = null;
        }
        this.httpClient = null;
        log.info("[dingtalk] DingTalk channel stopped");
    }

    // ==================== AI Card ====================

    /**
     * 是否启用了 AI Card 流式输出
     * <p>
     * 当 message_type=card 且 card_template_id 已配置时启用
     */
    public boolean isAICardEnabled() {
        return aiCardManager != null
                && "card".equals(getConfigString("message_type"))
                && getConfigString("card_template_id") != null;
    }

    /**
     * 获取 AI Card 管理器
     */
    public DingTalkAICardManager getAICardManager() {
        return aiCardManager;
    }

    /**
     * 获取 AI Card 模板 ID
     */
    public String getCardTemplateId() {
        return getConfigString("card_template_id");
    }

    /**
     * 获取机器人编码。
     * <p>
     * 钉钉自建应用机器人的 robotCode 大多数情况等于 AppKey（即 client_id），
     * 所以 robot_code 显式没配时直接 fallback 到 client_id —— 99% 的用户零配置就能用。
     * 第三方应用 / 单独申请的机器人才需要手动填 robot_code。
     */
    public String getRobotCode() {
        String configured = getConfigString("robot_code");
        if (configured != null && !configured.isBlank()) return configured;
        return getConfigString("client_id");
    }

    // ==================== StreamingChannelAdapter ====================

    /**
     * 流式处理 Agent 事件并渲染到钉钉
     * <p>
     * 渲染策略：
     * - AI Card 启用时：创建卡片 → 流式更新 → 完成/失败
     * - AI Card 未启用时：累积全部内容后通过 sessionWebhook 一次性发送
     */
    @Override
    public String processStream(Flux<StreamDelta> stream, ChannelMessage message, String conversationId) {
        if (isAICardEnabled()) {
            return processStreamWithAICard(stream, message);
        }
        // 无 AI Card：累积后发送（退化为文本模式，但仍走 streaming 获取内容）
        return processStreamAsText(stream, message);
    }

    /**
     * AI Card 流式渲染路径
     * <p>
     * 参考 MateClaw 的 _process_dingtalk_core() 模式：
     * 1. 创建"思考中..."卡片
     * 2. 消费事件流，流式更新卡片（500ms 节流）
     * 3. 完成时标记 FINISHED，异常时标记 FAILED
     * 4. 卡片创建失败时退化为文本模式
     */
    private String processStreamWithAICard(Flux<StreamDelta> stream, ChannelMessage message) {
        String cardTemplateId = getCardTemplateId();
        String robotCode = getRobotCode();
        String chatType = message.getChatId() != null ? "2" : "1";
        String dtConversationId = extractDingTalkConversationId(message);

        // Step 1: 创建并投放"思考中..."卡片
        String outTrackId = aiCardManager.createAndDeliverCard(
                cardTemplateId, dtConversationId, chatType, robotCode);

        if (outTrackId == null) {
            log.warn("[dingtalk] AI Card creation failed, falling back to text mode");
            return processStreamAsText(stream, message);
        }

        log.info("[dingtalk] AI Card streaming started: outTrackId={}", outTrackId);

        // Step 2: 消费事件流，流式更新卡片
        StringBuilder contentAccumulator = new StringBuilder();
        try {
            stream.doOnNext(delta -> {
                        if (delta.content() != null) {
                            contentAccumulator.append(delta.content());
                            aiCardManager.appendContent(outTrackId, delta.content(), false);
                        }
                    })
                    .doOnError(error -> {
                        log.error("[dingtalk] AI Card stream error: outTrackId={}, error={}",
                                outTrackId, error.getMessage());
                        aiCardManager.failCard(outTrackId, error.getMessage());
                    })
                    .blockLast(Duration.ofMinutes(5));

            // Step 3: 完成
            String finalContent = contentAccumulator.toString();
            if (finalContent.isBlank()) {
                finalContent = "（无回复内容）";
            }
            aiCardManager.finishCard(outTrackId, finalContent);

            log.info("[dingtalk] AI Card streaming completed: outTrackId={}, contentLen={}",
                    outTrackId, finalContent.length());
            return finalContent;

        } catch (Exception e) {
            log.error("[dingtalk] AI Card streaming failed: outTrackId={}, error={}",
                    outTrackId, e.getMessage(), e);
            aiCardManager.failCard(outTrackId, e.getMessage());

            // Tag the returned content with the "[错误] " prefix so
            // ChannelMessageRouter.isErrorReply flips status='error' on the
            // persisted row and BaseAgent.sanitizeForLlm filters it out of
            // the next turn's history. Without this, AICard partial output
            // (e.g. LLM 400'd mid-stream) would re-enter the prompt as a
            // valid assistant turn and re-trigger the same 400.
            String partial = contentAccumulator.toString();
            String errorPrefix = "[错误] AI Card streaming failed: " + e.getMessage();
            if (!partial.isBlank()) {
                return errorPrefix + "\n\n（已生成的部分内容，已忽略）\n" + partial;
            }
            throw new RuntimeException("AI Card streaming failed: " + e.getMessage(), e);
        }
    }

    /**
     * 文本模式流式处理：累积全部内容后通过 renderAndSend 发送
     */
    private String processStreamAsText(Flux<StreamDelta> stream, ChannelMessage message) {
        StringBuilder contentAccumulator = new StringBuilder();

        stream.doOnNext(delta -> {
                    if (delta.content() != null) {
                        contentAccumulator.append(delta.content());
                    }
                })
                .blockLast(Duration.ofMinutes(5));

        String finalContent = contentAccumulator.toString();
        if (!finalContent.isBlank()) {
            String replyTarget = message.getReplyToken() != null ? message.getReplyToken()
                    : (message.getChatId() != null ? message.getChatId() : message.getSenderId());
            renderAndSend(replyTarget, finalContent);
        }
        return finalContent;
    }

    /**
     * 从 rawPayload 中提取钉钉原生 conversationId
     */
    @SuppressWarnings("unchecked")
    private String extractDingTalkConversationId(ChannelMessage message) {
        if (message.getRawPayload() instanceof Map<?, ?> payload) {
            Object convId = payload.get("conversationId");
            if (convId instanceof String s) {
                return s;
            }
        }
        return message.getChatId() != null ? message.getChatId() : message.getSenderId();
    }

    /**
     * 处理来自钉钉 Webhook 的回调消息
     * 由 ChannelWebhookController 调用
     */
    @SuppressWarnings("unchecked")
    public void handleWebhook(Map<String, Object> payload) {
        try {
            String msgtype = (String) payload.get("msgtype");
            List<MessageContentPart> contentParts = new ArrayList<>();
            String textContent = null;

            if ("richText".equals(msgtype)) {
                // richText 消息：可包含文本 + 图片
                Map<String, Object> richTextBody = (Map<String, Object>) payload.get("richText");
                if (richTextBody != null) {
                    List<Map<String, Object>> richTextList = (List<Map<String, Object>>) richTextBody.get("richText");
                    if (richTextList != null) {
                        StringBuilder textBuilder = new StringBuilder();
                        for (Map<String, Object> item : richTextList) {
                            String text = (String) item.get("text");
                            if (text != null && !text.isBlank()) {
                                contentParts.add(MessageContentPart.text(text));
                                textBuilder.append(text);
                            }
                            String downloadCode = (String) item.get("downloadCode");
                            String pictureUrl = (String) item.get("pictureUrl");
                            if (downloadCode != null || pictureUrl != null) {
                                MessageContentPart imgPart = MessageContentPart.image(downloadCode, pictureUrl);
                                // Same as the standalone picture branch: vision needs bytes, not just an opaque
                                // downloadCode. Best-effort fetch; falls back to image-with-id if download fails.
                                if (downloadCode != null && !downloadCode.isBlank()) {
                                    DownloadedMedia media = downloadDingTalkMedia(downloadCode);
                                    if (media != null) {
                                        if (media.path() != null) imgPart.setPath(media.path());
                                        // Only override richText pictureUrl if we have a local-served URL.
                                        if (media.url() != null) imgPart.setFileUrl(media.url());
                                        if (media.fileName() != null) imgPart.setFileName(media.fileName());
                                        if (media.contentType() != null) imgPart.setContentType(media.contentType());
                                        if (media.size() > 0) imgPart.setFileSize(media.size());
                                    }
                                }
                                contentParts.add(imgPart);
                            }
                        }
                        textContent = textBuilder.toString().trim();
                    }
                }
            } else if ("audio".equals(msgtype)) {
                // 钉钉服务端已经把语音转写好放在 audio.recognition 里。这跟企业微信
                // 的 voice.content 是一个模式 —— webhook 自带 ASR 文本，0 STT 调用。
                Map<String, Object> audioBody = (Map<String, Object>) payload.get("audio");
                String recognition = audioBody != null ? (String) audioBody.get("recognition") : null;
                if (recognition != null && !recognition.isBlank()) {
                    textContent = recognition.trim();
                    contentParts.add(MessageContentPart.text(textContent));
                }
            } else if ("picture".equals(msgtype)) {
                // 单图消息。钉钉只给 downloadCode（不透明 ID），vision 模型直接读不了。
                // 调 /v1.0/robot/messageFiles/download 拿临时 URL，下载字节存本地，把
                // path 塞进 MessageContentPart 让多模态 LLM 能从磁盘读图。下载失败仍保留
                // image part（带 downloadCode）作为占位 — 至少消息不丢。
                Map<String, Object> pictureBody = (Map<String, Object>) payload.get("picture");
                String picDownloadCode = pictureBody != null ? (String) pictureBody.get("pictureDownloadCode") : null;
                String dlCode = pictureBody != null ? (String) pictureBody.get("downloadCode") : null;
                // Prefer the universal `downloadCode` — that's what the new
                // api.dingtalk.com/v1.0/robot/messageFiles/download API expects.
                // `pictureDownloadCode` is the legacy field for the old
                // oapi.dingtalk.com endpoint; passing it to the new API gets
                // HTTP 500 "unknownError".
                String code = dlCode != null ? dlCode : picDownloadCode;
                if (code != null && !code.isBlank()) {
                    DownloadedMedia media = downloadDingTalkMedia(code);
                    // mediaId stays as downloadCode for traceability; fileUrl is the
                    // browser-renderable URL (or null on download failure).
                    MessageContentPart imgPart = MessageContentPart.image(
                            code, media != null ? media.url() : null);
                    if (media != null) {
                        if (media.path() != null) imgPart.setPath(media.path());
                        if (media.fileName() != null) imgPart.setFileName(media.fileName());
                        if (media.contentType() != null) imgPart.setContentType(media.contentType());
                        if (media.size() > 0) imgPart.setFileSize(media.size());
                    }
                    contentParts.add(imgPart);
                    textContent = "[图片]";
                }
            } else {
                // 默认 text 消息
                Map<String, Object> msgBody = (Map<String, Object>) payload.get("text");
                textContent = msgBody != null ? (String) msgBody.get("content") : null;
                if (textContent != null && !textContent.isBlank()) {
                    contentParts.add(MessageContentPart.text(textContent.trim()));
                }
            }

            String senderId = (String) payload.get("senderStaffId");
            if (senderId == null) {
                senderId = (String) payload.get("senderId");
            }
            if (senderId == null) {
                log.warn("[dingtalk] No senderId found in webhook payload, ignoring message");
                return;
            }
            String senderNick = (String) payload.get("senderNick");
            String conversationId = (String) payload.get("conversationId");
            String msgId = (String) payload.get("msgId");
            String conversationType = (String) payload.get("conversationType");
            String sessionWebhook = (String) payload.get("sessionWebhook");

            if (contentParts.isEmpty() && (textContent == null || textContent.isBlank())) {
                log.debug("[dingtalk] Empty message content, ignoring");
                return;
            }

            String content = textContent != null ? textContent.trim() : "";

            ChannelMessage message = ChannelMessage.builder()
                    .messageId(msgId)
                    .channelType(CHANNEL_TYPE)
                    .senderId(senderId)
                    .senderName(senderNick)
                    .chatId("1".equals(conversationType) ? null : conversationId)
                    .content(content)
                    .contentType(contentParts.stream().anyMatch(p -> "image".equals(p.getType())) ? "image" : "text")
                    .contentParts(contentParts)
                    .inputMode("audio".equals(msgtype) ? "voice" : "text")
                    .timestamp(LocalDateTime.now())
                    .rawPayload(payload)
                    .build();

            // Reply token 编码上下文：sessionWebhook 走 Markdown 文本（无附件），
            // userId / conversationId 走 Robot API（能传 sampleFile / sampleImageMsg 真附件）。
            // 出站时 sendContentParts 解析这个 token 决定路径。格式是不透明字符串，sendMessage
            // 也能处理 —— 如果只是发 markdown 就直接走 webhook，不需要 access_token。
            String dtReplyToken = encodeReplyToken(sessionWebhook, senderId, conversationId, conversationType);
            message.setReplyToken(dtReplyToken);
            onMessage(message);

        } catch (Exception e) {
            log.error("[dingtalk] Failed to handle webhook: {}", e.getMessage(), e);
        }
    }

    @Override
    public void sendMessage(String targetId, String content) {
        if (httpClient == null) {
            log.warn("[dingtalk] Channel not started, cannot send message");
            return;
        }
        // Delegate to sendContentParts so the URL sniff for /api/v1/files/generated/ runs on
        // streaming-response markdown too — processStreamAsText's renderAndSend → sendMessage
        // path used to bypass it. content==null/blank short-circuits to no-op.
        if (content == null || content.isEmpty()) return;
        sendContentParts(targetId, List.of(MessageContentPart.text(content)));
    }

    /** Existing sessionWebhook reply path, factored out so sendMessage and sendContentParts share it. */
    private void sendMarkdownViaWebhook(String webhookUrl, String content) {
        String messageType = getConfigString("message_type", "markdown");
        try {
            String jsonBody;
            if ("markdown".equals(messageType) || "card".equals(messageType)) {
                jsonBody = objectMapper.writeValueAsString(Map.of(
                        "msgtype", "markdown",
                        "markdown", Map.of("title", "MateClaw", "text", content)
                ));
            } else {
                jsonBody = objectMapper.writeValueAsString(Map.of(
                        "msgtype", "text",
                        "text", Map.of("content", content)
                ));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[dingtalk] Send message failed: status={}, body={}", response.statusCode(), response.body());
            } else {
                log.debug("[dingtalk] Message sent successfully via sessionWebhook");
            }
        } catch (Exception e) {
            log.error("[dingtalk] Failed to send message: {}", e.getMessage(), e);
        }
    }

    @Override
    public void sendContentParts(String targetId, List<MessageContentPart> parts) {
        if (httpClient == null) {
            log.warn("[dingtalk] Channel not started, cannot send message");
            return;
        }

        ReplyContext ctx = decodeReplyToken(targetId);

        // Phase 1 — walk parts. Text concatenates into the markdown reply; image/file
        // parts (with resolvable bytes) become upload jobs. Text segments are also
        // scanned for /api/v1/files/generated/{id} URLs (DocxRenderTool output etc.):
        // hits are pulled out of the cache and queued as file uploads, with the URL
        // replaced inline by a "📎 filename" badge so the user doesn't see a stale link.
        List<UploadJob> uploadJobs = new ArrayList<>();
        StringBuilder markdown = new StringBuilder();
        for (MessageContentPart part : parts) {
            if (part == null) continue;
            switch (part.getType()) {
                case "text" -> {
                    String text = part.getText();
                    if (text == null || text.isEmpty()) break;
                    markdown.append(sniffGeneratedFiles(text, uploadJobs));
                }
                case "image" -> {
                    byte[] bytes = resolveBytes(part);
                    String fileName = part.getFileName() != null ? part.getFileName() : "image.png";
                    if (bytes != null) {
                        uploadJobs.add(new UploadJob(bytes, fileName, "image"));
                    } else if (part.getFileUrl() != null) {
                        markdown.append("\n![图片](").append(part.getFileUrl()).append(")\n");
                    } else {
                        markdown.append("\n[图片]\n");
                    }
                }
                case "file" -> {
                    byte[] bytes = resolveBytes(part);
                    String fileName = part.getFileName() != null ? part.getFileName() : "file.bin";
                    if (bytes != null) {
                        uploadJobs.add(new UploadJob(bytes, fileName, "file"));
                    } else {
                        markdown.append("\n[文件: ").append(fileName).append("]\n");
                    }
                }
                default -> { if (part.getText() != null) markdown.append(part.getText()); }
            }
        }

        // Phase 2 — send the markdown body (if any) via sessionWebhook.
        String md = markdown.toString().trim();
        if (!md.isEmpty()) {
            if (ctx.webhook != null && ctx.webhook.startsWith("http")) {
                sendMarkdownViaWebhook(ctx.webhook, md);
            } else {
                // No sessionWebhook — fall back to Robot API text send. proactiveSend()
                // re-decodes the same token and routes by userId/conv. Don't call back into
                // sendMessage() here — sendMessage() now delegates to sendContentParts(),
                // which would recurse infinitely.
                proactiveSend(ctx.userId != null ? ctx.userId : targetId, md);
            }
        }

        // Phase 3 — upload + send media jobs via Robot API. Skips silently and falls back
        // to a markdown placeholder if Robot API isn't usable (no robot_code, or
        // no userId / conversationId in the reply context).
        if (!uploadJobs.isEmpty()) {
            sendUploadJobs(ctx, uploadJobs);
        }
    }

    /** Scan a text fragment for `/api/v1/files/generated/{id}` URLs; return the rewritten text
     *  with each hit replaced by "📎 filename" and the corresponding bytes pushed onto jobs. */
    private String sniffGeneratedFiles(String text, List<UploadJob> jobs) {
        if (generatedFileCache == null) return text;
        java.util.regex.Matcher m = GENERATED_URL_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String id = m.group(1);
            var entry = generatedFileCache.get(id).orElse(null);
            if (entry != null) {
                String type = isImageMime(entry.mimeType()) ? "image" : "file";
                jobs.add(new UploadJob(entry.bytes(), entry.filename(), type));
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("📎 " + entry.filename()));
            } else {
                // Cache miss / expired — leave the URL alone so user can still try clicking.
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final java.util.regex.Pattern GENERATED_URL_PATTERN =
            java.util.regex.Pattern.compile("(?:https?://[^/\\s)\\]]+)?/api/v1/files/generated/([a-zA-Z0-9-]+)");

    private static boolean isImageMime(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
    }

    /** Pull bytes from disk (part.path) or from the in-memory generated-file cache (part.fileUrl). */
    private byte[] resolveBytes(MessageContentPart part) {
        if (part == null) return null;
        // Disk path: trust the agent's filesystem write, but guard against blowup on big files.
        String path = part.getPath();
        if (path != null && !path.isBlank()) {
            try {
                java.nio.file.Path p = java.nio.file.Paths.get(path);
                if (java.nio.file.Files.exists(p) && java.nio.file.Files.size(p) <= DingTalkMediaUploader.MAX_FILE_BYTES) {
                    return java.nio.file.Files.readAllBytes(p);
                }
            } catch (Exception e) {
                log.debug("[dingtalk] resolveBytes from path failed: {}", e.getMessage());
            }
        }
        // GeneratedFileCache URL: /api/v1/files/generated/{id}
        String url = part.getFileUrl();
        if (url != null && generatedFileCache != null) {
            java.util.regex.Matcher m = GENERATED_URL_PATTERN.matcher(url);
            if (m.find()) {
                var entry = generatedFileCache.get(m.group(1)).orElse(null);
                if (entry != null) return entry.bytes();
            }
        }
        return null;
    }

    /** Records to keep sendContentParts readable. */
    private record UploadJob(byte[] bytes, String fileName, String type) {}

    /** Decoded reply context, populated from {@link #encodeReplyToken}. */
    private static class ReplyContext {
        String webhook;
        String userId;
        String convId;
        /** "1" = one-to-one, "2" = group; empty/null = unknown. */
        String chatType;
    }

    /**
     * Reply token is JSON now: {wh, user, conv, ct}. Old bare-URL tokens (e.g. from
     * an in-flight conversation that started before this change) are still accepted —
     * the leading-{ check tells JSON apart from a raw HTTP URL.
     */
    private String encodeReplyToken(String webhook, String userId, String conversationId, String chatType) {
        java.util.LinkedHashMap<String, String> ctx = new java.util.LinkedHashMap<>();
        if (webhook != null) ctx.put("wh", webhook);
        if (userId != null) ctx.put("user", userId);
        if (conversationId != null) ctx.put("conv", conversationId);
        if (chatType != null) ctx.put("ct", chatType);
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            return webhook != null ? webhook : "";
        }
    }

    @SuppressWarnings("unchecked")
    private ReplyContext decodeReplyToken(String token) {
        ReplyContext c = new ReplyContext();
        if (token == null || token.isBlank()) return c;
        String trimmed = token.trim();
        if (!trimmed.startsWith("{")) {
            // Legacy bare sessionWebhook URL or userId — treat http-prefixed as webhook,
            // otherwise as userId for proactive send.
            if (trimmed.startsWith("http")) c.webhook = trimmed;
            else c.userId = trimmed;
            return c;
        }
        try {
            Map<String, Object> m = objectMapper.readValue(trimmed, Map.class);
            c.webhook = (String) m.get("wh");
            c.userId = (String) m.get("user");
            c.convId = (String) m.get("conv");
            c.chatType = (String) m.get("ct");
        } catch (Exception e) {
            c.webhook = trimmed;
        }
        return c;
    }

    /**
     * Upload each job to DingTalk's media endpoint, then send a sampleFile / sampleImageMsg
     * via the Robot API. Falls back gracefully (one log line per skipped job) if the channel
     * isn't configured for proactive send (no robot_code, or no usable target id).
     */
    private void sendUploadJobs(ReplyContext ctx, List<UploadJob> jobs) {
        String robotCode = getRobotCode();
        if (robotCode == null || robotCode.isBlank()) {
            log.warn("[dingtalk] {} attachment(s) skipped: robot_code not configured (Robot API needed for sampleFile / sampleImageMsg)", jobs.size());
            return;
        }
        boolean isGroup = "2".equals(ctx.chatType) && ctx.convId != null && !ctx.convId.isBlank();
        boolean isOneToOne = ctx.userId != null && !ctx.userId.isBlank();
        if (!isGroup && !isOneToOne) {
            log.warn("[dingtalk] {} attachment(s) skipped: no usable userId / conversationId in reply context", jobs.size());
            return;
        }

        String accessToken = getDingTalkAccessToken();
        if (accessToken == null) {
            log.warn("[dingtalk] {} attachment(s) skipped: failed to get access_token", jobs.size());
            return;
        }

        for (UploadJob job : jobs) {
            try {
                String mediaId = "image".equals(job.type)
                        ? mediaUploader.uploadImage(job.bytes, job.fileName, accessToken)
                        : mediaUploader.uploadFile(job.bytes, job.fileName, accessToken);
                if (mediaId == null) {
                    log.warn("[dingtalk] upload failed for {}, attachment skipped", job.fileName);
                    continue;
                }

                String msgKey;
                String msgParam;
                if ("image".equals(job.type)) {
                    msgKey = "sampleImageMsg";
                    msgParam = objectMapper.writeValueAsString(Map.of("photoURL", mediaId));
                } else {
                    msgKey = "sampleFile";
                    msgParam = objectMapper.writeValueAsString(Map.of(
                            "mediaId", mediaId,
                            "fileName", job.fileName,
                            "fileType", inferFileType(job.fileName)
                    ));
                }

                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("robotCode", robotCode);
                body.put("msgKey", msgKey);
                body.put("msgParam", msgParam);
                String endpoint;
                if (isGroup) {
                    body.put("openConversationId", ctx.convId);
                    endpoint = "https://api.dingtalk.com/v1.0/robot/groupMessages/send";
                } else {
                    body.put("userIds", List.of(ctx.userId));
                    endpoint = "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend";
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .header("x-acs-dingtalk-access-token", accessToken)
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.warn("[dingtalk] sampleFile send HTTP {}: {}", response.statusCode(), response.body());
                } else {
                    log.info("[dingtalk] attachment sent: {} ({})", job.fileName, job.type);
                }
            } catch (Exception e) {
                log.warn("[dingtalk] sendUploadJobs error for {}: {}", job.fileName, e.getMessage());
            }
        }
    }

    /** sampleFile expects fileType as a short extension string (docx / pdf / xlsx / ...). */
    private static String inferFileType(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }

    // ==================== 入站媒体下载 ====================

    /**
     * Result of a media download: local path (for vision pipeline) + HTTP URL
     * (for browser rendering) + metadata to populate MessageContentPart so the
     * UI doesn't fall back to "unknown" filenames.
     */
    private record DownloadedMedia(String path, String url, String fileName,
                                   String contentType, long size) {
    }

    /**
     * 把入站消息里的 downloadCode 解析成本地文件路径 + 浏览器可访问的 URL。
     * <p>
     * 钉钉的 inbound 图片 / 文件不直接给字节，只给 downloadCode（不透明的内部 ID）。
     * 要让 vision 模型读图、或落盘存档给用户回看，必须先调
     * {@code /v1.0/robot/messageFiles/download} 拿一个短期 downloadUrl，再 GET 拉字节。
     * <p>
     * 字节落两份：磁盘 (~/.mateclaw/media/dingtalk/) 给 vision 读 path，
     * GeneratedFileCache 给 UI 通过 /api/v1/files/generated/{id} 渲染（10 min TTL）。
     * <p>
     * 失败 / 没配 robot_code / token 拿不到 → 返回 null，调用方继续把 downloadCode 当占位用。
     */
    private DownloadedMedia downloadDingTalkMedia(String downloadCode) {
        if (downloadCode == null || downloadCode.isBlank()) return null;
        String robotCode = getRobotCode();
        if (robotCode == null || robotCode.isBlank()) {
            log.debug("[dingtalk] media download skipped: no robot_code");
            return null;
        }
        String accessToken = getDingTalkAccessToken();
        if (accessToken == null) {
            log.debug("[dingtalk] media download skipped: no access_token");
            return null;
        }

        try {
            // Step 1: ask DingTalk for a short-TTL download URL
            String body = objectMapper.writeValueAsString(Map.of(
                    "downloadCode", downloadCode,
                    "robotCode", robotCode
            ));
            HttpRequest infoReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.dingtalk.com/v1.0/robot/messageFiles/download"))
                    .header("Content-Type", "application/json")
                    .header("x-acs-dingtalk-access-token", accessToken)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> infoResp = httpClient.send(infoReq, HttpResponse.BodyHandlers.ofString());
            if (infoResp.statusCode() != 200) {
                // Mask the codes so the log doesn't carry full credentials but is still
                // useful for diagnosing whether we sent the right shape.
                String codeTail = downloadCode.length() > 8
                        ? "..." + downloadCode.substring(downloadCode.length() - 8) : downloadCode;
                String robotTail = robotCode.length() > 6
                        ? "..." + robotCode.substring(robotCode.length() - 6) : robotCode;
                log.warn("[dingtalk] media download info HTTP {} (downloadCode={}, robotCode={}): {}",
                        infoResp.statusCode(), codeTail, robotTail, infoResp.body());
                return null;
            }
            Map<?, ?> infoData = objectMapper.readValue(infoResp.body(), Map.class);
            String downloadUrl = (String) infoData.get("downloadUrl");
            if (downloadUrl == null || downloadUrl.isBlank()) {
                log.warn("[dingtalk] media download info missing downloadUrl: {}", infoResp.body());
                return null;
            }

            // Step 2: pull bytes from the signed URL
            HttpRequest dlReq = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> dlResp = httpClient.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());
            if (dlResp.statusCode() != 200) {
                log.warn("[dingtalk] media download fetch HTTP {}", dlResp.statusCode());
                return null;
            }
            byte[] bytes = dlResp.body();

            // Step 3: persist to ~/.mateclaw/media/dingtalk/. Filename derived from
            // downloadCode (sanitised) + extension inferred from Content-Type so vision
            // pipelines that key on extension still work.
            String contentType = dlResp.headers().firstValue("Content-Type").orElse("image/jpeg");
            String ext = "jpg";
            if (contentType.contains("png")) ext = "png";
            else if (contentType.contains("gif")) ext = "gif";
            else if (contentType.contains("webp")) ext = "webp";
            else if (contentType.contains("pdf")) ext = "pdf";

            java.nio.file.Path mediaDir = java.nio.file.Paths.get(
                    System.getProperty("user.home"), ".mateclaw", "media", "dingtalk");
            java.nio.file.Files.createDirectories(mediaDir);
            String safeCode = downloadCode.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (safeCode.length() > 32) safeCode = safeCode.substring(safeCode.length() - 32);
            java.nio.file.Path filePath = mediaDir.resolve(safeCode + "." + ext);
            java.nio.file.Files.write(filePath, bytes);

            // Step 4: also stuff into GeneratedFileCache so the UI can render the image
            // via /api/v1/files/generated/{id}. Best-effort — failure here doesn't kill
            // the vision pipeline (path is still useful).
            String url = null;
            if (generatedFileCache != null) {
                try {
                    String fileName = safeCode + "." + ext;
                    String id = generatedFileCache.put(bytes, fileName, contentType);
                    url = "/api/v1/files/generated/" + id;
                } catch (Exception cacheEx) {
                    log.debug("[dingtalk] cache put failed: {}", cacheEx.getMessage());
                }
            }

            String fileName = safeCode + "." + ext;
            log.info("[dingtalk] media downloaded: {} ({} bytes, {}) url={}",
                    fileName, bytes.length, contentType, url);
            return new DownloadedMedia(
                    filePath.toAbsolutePath().toString(), url, fileName, contentType, bytes.length);
        } catch (Exception e) {
            log.warn("[dingtalk] media download failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 主动推送 ====================

    @Override
    public boolean supportsProactiveSend() {
        return true;
    }

    /**
     * 主动推送消息
     * <p>
     * targetId 可以是：
     * - sessionWebhook URL（以 http 开头）：直接通过 Webhook 发送
     * - conversationId：通过 Robot API 的 orgGroupSend / privateSend 发送（需 access_token）
     */
    @Override
    public void proactiveSend(String targetId, String content) {
        if (httpClient == null) {
            log.warn("[dingtalk] Channel not started, cannot proactive send");
            return;
        }

        // targetId may be: sessionWebhook URL, raw userId, or our JSON-encoded reply token
        // (after the replyToken refactor). Decode unifies all three.
        ReplyContext ctx = decodeReplyToken(targetId);
        if (ctx.webhook != null && ctx.webhook.startsWith("http")) {
            sendMarkdownViaWebhook(ctx.webhook, content);
            return;
        }

        // 通过 Robot API 发送：获取 access_token 后调用 /v1.0/robot/oToMessages/batchSend
        String robotCode = getRobotCode();
        if (robotCode == null || robotCode.isBlank()) {
            log.warn("[dingtalk] robot_code not configured, no usable webhook either — proactive send skipped");
            return;
        }
        String resolvedUserId = ctx.userId != null ? ctx.userId : targetId;

        try {
            String accessToken = getDingTalkAccessToken();
            if (accessToken == null) {
                log.error("[dingtalk] Failed to obtain access_token for proactive send");
                return;
            }

            String messageType = getConfigString("message_type", "markdown");
            Map<String, Object> msgParam;
            String msgKey;
            if ("markdown".equals(messageType) || "card".equals(messageType)) {
                msgKey = "sampleMarkdown";
                msgParam = Map.of("title", "MateClaw", "text", content);
            } else {
                msgKey = "sampleText";
                msgParam = Map.of("content", content);
            }

            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "robotCode", robotCode,
                    "userIds", List.of(resolvedUserId),
                    "msgKey", msgKey,
                    "msgParam", objectMapper.writeValueAsString(msgParam)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend"))
                    .header("Content-Type", "application/json")
                    .header("x-acs-dingtalk-access-token", accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[dingtalk] Proactive send failed: status={}, body={}", response.statusCode(), response.body());
            } else {
                log.debug("[dingtalk] Proactive message sent to {}", targetId);
            }
        } catch (Exception e) {
            log.error("[dingtalk] Failed to proactive send: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取钉钉 access_token（用于 Robot API）
     */
    private String getDingTalkAccessToken() {
        // 如果有 AI Card Manager，复用其 token
        if (aiCardManager != null) {
            return aiCardManager.ensureAccessToken();
        }

        String clientId = getConfigString("client_id");
        String clientSecret = getConfigString("client_secret");
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "appKey", clientId,
                    "appSecret", clientSecret
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            return (String) result.get("accessToken");
        } catch (Exception e) {
            log.error("[dingtalk] Failed to get access_token: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    // ==================== Stream 断线重连 ====================

    /**
     * Stream 连接断开时由外部调用（或内部检测到断开时调用）
     * <p>
     * 触发指数退避重连：重新初始化 httpClient 和 AI Card Manager
     */
    public void notifyStreamDisconnected(String reason) {
        onDisconnected("Stream disconnected: " + reason);
    }

    @Override
    protected void doReconnect() {
        log.info("[dingtalk] Reconnecting: {} (mode={})", channelEntity.getName(), getConnectionMode());
        // 完整重建：doStop() + doStart()（默认 AbstractChannelAdapter 行为）
        super.doReconnect();
    }
}
