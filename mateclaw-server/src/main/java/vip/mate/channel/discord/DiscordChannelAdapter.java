package vip.mate.channel.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import okhttp3.OkHttpClient;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Discord 渠道适配器 — 基于 JDA Gateway WebSocket
 * <p>
 * 通过 Discord Gateway（WebSocket 长连接）接收消息，通过 REST API 发送消息。
 * JDA 内置自动重连机制，无需手动管理 WebSocket 生命周期。
 * <p>
 * 配置项（configJson）：
 * - bot_token: Discord Bot Token（必填）
 * - accept_bot_messages: 是否接收其他 Bot 消息，默认 false
 *
 * @author MateClaw Team
 */
@Slf4j
public class DiscordChannelAdapter extends AbstractChannelAdapter {

    public static final String CHANNEL_TYPE = "discord";

    private volatile JDA jda;
    private volatile String selfId;

    /** 媒体下载用 HttpClient（复用 http_proxy 配置） */
    private volatile HttpClient mediaHttpClient;

    /** 已处理消息去重（LRU，最多保留 500 条） */
    private final Set<String> processedMessageIds = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 500;
        }
    });

    public DiscordChannelAdapter(ChannelEntity channelEntity,
                                 ChannelMessageRouter messageRouter,
                                 ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
    }

    @Override
    protected void doStart() {
        String botToken = getConfigString("bot_token");
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException("Discord channel requires bot_token in configJson");
        }

        try {
            JDABuilder builder = JDABuilder.createDefault(botToken)
                    .enableIntents(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.DIRECT_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT
                    )
                    .disableCache(
                            CacheFlag.VOICE_STATE,
                            CacheFlag.EMOJI,
                            CacheFlag.STICKER,
                            CacheFlag.SCHEDULED_EVENTS
                    )
                    .setAutoReconnect(true)
                    .addEventListeners(new DiscordEventListener());

            // 代理配置：统一解析，同时应用到 JDA（OkHttp）和媒体下载（HttpClient）
            Proxy proxy = parseProxy();
            if (proxy != null) {
                OkHttpClient okHttpClient = new OkHttpClient.Builder().proxy(proxy).build();
                builder.setHttpClientBuilder(okHttpClient.newBuilder());
            }

            HttpClient.Builder mediaClientBuilder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (proxy != null) {
                mediaClientBuilder.proxy(java.net.ProxySelector.of(
                        (InetSocketAddress) proxy.address()));
            }
            this.mediaHttpClient = mediaClientBuilder.build();

            this.jda = builder.build();

            // 等待 JDA 就绪（最多 30 秒）
            this.jda.awaitReady();
            this.selfId = this.jda.getSelfUser().getId();

            log.info("[discord] Discord Gateway connected, bot: {} ({})",
                    jda.getSelfUser().getName(), selfId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Discord JDA startup interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Discord JDA startup failed: " + e.getMessage(), e);
        }
    }

    @Override
    protected void doStop() {
        if (jda != null) {
            jda.shutdown();
            try {
                // 等待最多 5 秒优雅关闭
                if (!jda.awaitShutdown(java.time.Duration.ofSeconds(5))) {
                    jda.shutdownNow();
                }
            } catch (InterruptedException e) {
                jda.shutdownNow();
                Thread.currentThread().interrupt();
            }
            jda = null;
        }
        selfId = null;
        mediaHttpClient = null;
        processedMessageIds.clear();
        log.info("[discord] Discord channel stopped");
    }

    /**
     * 从 configJson.http_proxy 解析代理，返回 null 表示直连。
     */
    private Proxy parseProxy() {
        String httpProxy = getConfigString("http_proxy");
        if (httpProxy == null || httpProxy.isBlank()) {
            return null;
        }
        try {
            URI proxyUri = URI.create(httpProxy);
            String proxyHost = proxyUri.getHost();
            int proxyPort = proxyUri.getPort();
            if (proxyHost != null && proxyPort > 0) {
                log.info("[discord] Using HTTP proxy: {}:{}", proxyHost, proxyPort);
                return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            }
            log.warn("[discord] Invalid http_proxy (missing host or port): '{}'", httpProxy);
        } catch (Exception e) {
            log.warn("[discord] Invalid http_proxy '{}': {}", httpProxy, e.getMessage());
        }
        return null;
    }

    // ==================== 消息发送 ====================

    @Override
    public void sendMessage(String targetId, String content) {
        JDA currentJda = this.jda;
        if (currentJda == null) {
            log.warn("[discord] JDA not ready, cannot send message");
            return;
        }

        try {
            MessageChannel channel = currentJda.getChannelById(MessageChannel.class, targetId);
            if (channel == null) {
                log.warn("[discord] Channel not found: {}", targetId);
                return;
            }

            // 显示输入指示
            channel.sendTyping().queue();

            channel.sendMessage(content).queue(
                    success -> log.debug("[discord] Message sent to {}", targetId),
                    error -> log.warn("[discord] Failed to send message to {}: {}", targetId, error.getMessage())
            );
        } catch (Exception e) {
            log.error("[discord] Failed to send message: {}", e.getMessage(), e);
        }
    }

    @Override
    public void sendContentParts(String targetId, List<MessageContentPart> parts) {
        JDA currentJda = this.jda;
        if (currentJda == null) {
            log.warn("[discord] JDA not ready, cannot send message");
            return;
        }

        MessageChannel channel = currentJda.getChannelById(MessageChannel.class, targetId);
        if (channel == null) {
            log.warn("[discord] Channel not found: {}", targetId);
            return;
        }

        StringBuilder text = new StringBuilder();
        List<MediaPart> mediaParts = new ArrayList<>();

        for (MessageContentPart part : parts) {
            if (part == null) continue;
            switch (part.getType()) {
                case "text" -> { if (part.getText() != null) text.append(part.getText()); }
                case "image", "file", "audio", "video" -> {
                    String url = part.getFileUrl();
                    String fileName = part.getFileName();
                    if (url != null) {
                        mediaParts.add(new MediaPart(url, fileName, part.getType()));
                    } else {
                        text.append("\n[").append(part.getType()).append("]");
                    }
                }
                default -> { if (part.getText() != null) text.append(part.getText()); }
            }
        }

        // 先发文本
        String content = text.toString().trim();
        if (content.length() > 2000) {
            renderAndSend(targetId, content);
        } else if (!content.isEmpty()) {
            sendMessage(targetId, content);
        }

        // 逐个上传媒体文件作为 Discord attachment
        for (MediaPart media : mediaParts) {
            sendMediaAttachment(channel, media);
        }
    }

    /**
     * 将媒体文件作为 Discord attachment 上传发送。
     * <p>
     * 参考 MateClaw：远程 URL 先下载到临时文件，再通过 JDA FileUpload 上传。
     */
    private void sendMediaAttachment(MessageChannel channel, MediaPart media) {
        Path tempFile = null;
        try {
            String url = media.url;

            if (url.startsWith("file://")) {
                // 本地文件
                Path localPath = Path.of(URI.create(url));
                String fileName = media.fileName != null ? media.fileName : localPath.getFileName().toString();
                channel.sendFiles(FileUpload.fromData(localPath, fileName)).queue(
                        ok -> log.debug("[discord] Media uploaded: {}", fileName),
                        err -> {
                            log.warn("[discord] Failed to upload media: {}", err.getMessage());
                            sendMediaFallbackText(channel, media);
                        }
                );
                return;
            }

            if (url.startsWith("http://") || url.startsWith("https://")) {
                // 远程 URL：下载到临时文件后上传
                String fileName = media.fileName;
                if (fileName == null || fileName.isBlank()) {
                    String path = URI.create(url).getPath();
                    fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : "file";
                    if (fileName.isBlank()) fileName = "file";
                }

                // 推导文件后缀
                String suffix = "";
                int dotIdx = fileName.lastIndexOf('.');
                if (dotIdx >= 0) {
                    suffix = fileName.substring(dotIdx);
                }

                tempFile = Files.createTempFile("discord-media-", suffix);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<InputStream> resp = mediaHttpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

                if (resp.statusCode() == 200) {
                    try (InputStream is = resp.body()) {
                        Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    String finalName = fileName;
                    Path finalTemp = tempFile;
                    channel.sendFiles(FileUpload.fromData(tempFile, finalName)).queue(
                            ok -> {
                                log.debug("[discord] Media uploaded: {}", finalName);
                                deleteTempQuietly(finalTemp);
                            },
                            err -> {
                                log.warn("[discord] Failed to upload media {}: {}", finalName, err.getMessage());
                                deleteTempQuietly(finalTemp);
                                sendMediaFallbackText(channel, media);
                            }
                    );
                    tempFile = null; // 清理交给回调
                } else {
                    log.warn("[discord] Failed to download media (status={}): {}", resp.statusCode(), url);
                    sendMediaFallbackText(channel, media);
                }
                return;
            }

            // 未知协议，降级为文本
            sendMediaFallbackText(channel, media);

        } catch (Exception e) {
            log.error("[discord] Failed to send media attachment: {}", e.getMessage(), e);
            sendMediaFallbackText(channel, media);
        } finally {
            if (tempFile != null) {
                deleteTempQuietly(tempFile);
            }
        }
    }

    /**
     * 媒体上传/下载失败时降级发送 URL 文本，防止消息静默丢失。
     */
    private void sendMediaFallbackText(MessageChannel channel, MediaPart media) {
        try {
            channel.sendMessage("[" + media.type + ": " + media.url + "]").queue();
        } catch (Exception e) {
            log.warn("[discord] Fallback text also failed: {}", e.getMessage());
        }
    }

    private static void deleteTempQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {}
    }

    private record MediaPart(String url, String fileName, String type) {}

    // ==================== 主动推送 ====================

    @Override
    public boolean supportsProactiveSend() {
        return true;
    }

    @Override
    public void proactiveSend(String targetId, String content) {
        sendMessage(targetId, content);
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    /**
     * Discord enforces a single Gateway session per bot token (any duplicate
     * {@code IDENTIFY} closes the previous shard) and there is no active
     * webhook fallback in this adapter — the legacy webhook endpoint is
     * a no-op. The leader gate ensures only one node holds the Gateway
     * connection at a time.
     */
    @Override
    public boolean requiresSingleLeader() {
        return true;
    }

    // ==================== Webhook 兼容（保留接口，不再使用） ====================

    /**
     * 处理 Discord Webhook 回调（已废弃，保留兼容性）
     * <p>
     * Discord 已切换为 Gateway WebSocket 模式，不再需要 Webhook 回调。
     * 此方法仅在 webhook 端点被调用时记录警告日志。
     */
    public void handleWebhook(Map<String, Object> payload) {
        log.warn("[discord] Received webhook callback, but Discord is now using Gateway mode. " +
                "This webhook endpoint is deprecated.");
    }

    // ==================== JDA 事件监听器 ====================

    private class DiscordEventListener extends ListenerAdapter {

        @Override
        public void onReady(ReadyEvent event) {
            log.info("[discord] Gateway ready, guilds: {}", event.getGuildTotalCount());
            connectionState.set(ConnectionState.CONNECTED);
            lastError = null;
            backoff.reset();
        }

        @Override
        public void onSessionDisconnect(SessionDisconnectEvent event) {
            log.warn("[discord] Gateway disconnected (JDA will auto-reconnect)");
            connectionState.set(ConnectionState.RECONNECTING);
        }

        @Override
        public void onSessionResume(SessionResumeEvent event) {
            log.info("[discord] Gateway session resumed");
            connectionState.set(ConnectionState.CONNECTED);
            lastError = null;
        }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            try {
                processIncomingMessage(event);
            } catch (Exception e) {
                log.error("[discord] Failed to process message: {}", e.getMessage(), e);
            }
        }
    }

    private void processIncomingMessage(MessageReceivedEvent event) {
        Message message = event.getMessage();
        User author = message.getAuthor();

        // 忽略自身消息
        if (author.getId().equals(selfId)) {
            return;
        }

        // 忽略其他 Bot 消息（除非配置允许）
        if (author.isBot() && !getConfigBoolean("accept_bot_messages", false)) {
            return;
        }

        // 去重
        String msgId = message.getId();
        synchronized (processedMessageIds) {
            if (processedMessageIds.contains(msgId)) {
                return;
            }
            processedMessageIds.add(msgId);
        }

        String channelId = message.getChannel().getId();
        String guildId = message.isFromGuild() ? message.getGuild().getId() : null;
        String senderId = author.getId();
        String senderName = author.getName();

        // 处理消息内容：清理 Bot mention
        String textContent = message.getContentRaw();
        if (selfId != null) {
            // 清理 <@botId> 和 <@!botId> mention 标记
            textContent = textContent.replaceAll("<@!?" + selfId + ">", "").trim();
        }

        // 构建 contentParts
        List<MessageContentPart> contentParts = new ArrayList<>();

        if (!textContent.isBlank()) {
            contentParts.add(MessageContentPart.text(textContent));
        }

        // 解析附件
        for (Message.Attachment attachment : message.getAttachments()) {
            String url = attachment.getUrl();
            String fileName = attachment.getFileName();
            String contentType = attachment.getContentType();
            long size = attachment.getSize();

            MessageContentPart part;
            if (attachment.isImage()) {
                part = MessageContentPart.image(attachment.getId(), url);
            } else if (attachment.isVideo()) {
                part = MessageContentPart.video(attachment.getId(), fileName);
                part.setFileUrl(url);
            } else if (contentType != null && contentType.startsWith("audio/")) {
                part = MessageContentPart.audio(attachment.getId(), fileName);
                part.setFileUrl(url);
            } else {
                part = MessageContentPart.file(attachment.getId(), fileName, contentType);
                part.setFileUrl(url);
            }
            part.setFileName(fileName);
            if (contentType != null) part.setContentType(contentType);
            part.setFileSize(size);
            contentParts.add(part);
        }

        if (contentParts.isEmpty()) {
            return;
        }

        // 文本摘要
        String textSummary = textContent.isBlank() ? "" : textContent;
        if (textSummary.isBlank() && !message.getAttachments().isEmpty()) {
            textSummary = "[附件 x" + message.getAttachments().size() + "]";
        }

        // 判断是否为 Bot mention（群聊中）
        boolean isBotMentioned = message.getMentions().isMentioned(jda.getSelfUser());

        ChannelMessage channelMessage = ChannelMessage.builder()
                .messageId(msgId)
                .channelType(CHANNEL_TYPE)
                .senderId(senderId)
                .senderName(senderName)
                .chatId(guildId != null ? channelId : null) // 群聊用 channelId，私聊为 null
                .content(textSummary)
                .contentType(contentParts.stream().anyMatch(p -> !"text".equals(p.getType())) ? "mixed" : "text")
                .contentParts(contentParts)
                .timestamp(LocalDateTime.now())
                .replyToken(channelId)
                .rawPayload(Map.of(
                        "message_id", msgId,
                        "channel_id", channelId,
                        "guild_id", guildId != null ? guildId : "",
                        "is_dm", !message.isFromGuild(),
                        "bot_mentioned", isBotMentioned
                ))
                .build();

        onMessage(channelMessage);
    }
}
