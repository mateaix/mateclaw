package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import vip.mate.agent.AgentService;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.stt.SttService;
import vip.mate.tts.TtsService;
import vip.mate.workspace.conversation.ConversationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talk Mode WebSocket Handler
 * <p>
 * 处理语音交互的完整循环：
 * 1. 接收前端音频 binary frame
 * 2. STT 转文字
 * 3. Agent 对话
 * 4. TTS 合成音频
 * 5. 推送音频 + 文字回前端
 * <p>
 * 前端初始化时发送 JSON text frame 指定 agentId 和 conversationId：
 * {"type":"init","agentId":1,"conversationId":"talk-xxx"}
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TalkModeWebSocketHandler extends AbstractWebSocketHandler {

    private final SttService sttService;
    private final TtsService ttsService;
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ConversationCompletionPublisher completionPublisher;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** 每个 WebSocket 会话的上下文 */
    private final ConcurrentHashMap<String, TalkSession> sessions = new ConcurrentHashMap<>();

    private record TalkSession(Long agentId, String conversationId, String username) {}

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[TalkMode] WebSocket connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        try {
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            String type = (String) data.get("type");

            if ("init".equals(type)) {
                Object rawAgentId = data.get("agentId");
                Long agentId = (rawAgentId != null && !rawAgentId.toString().isBlank())
                        ? Long.valueOf(rawAgentId.toString()) : null;
                String conversationId = (String) data.getOrDefault("conversationId", "talk-" + session.getId());
                String username = (String) data.getOrDefault("username", "anonymous");

                if (agentId == null) {
                    sendJson(session, Map.of("type", "error", "message", "agentId is required"));
                    return;
                }

                sessions.put(session.getId(), new TalkSession(agentId, conversationId, username));
                sendJson(session, Map.of("type", "ready", "conversationId", conversationId));
                log.info("[TalkMode] Session initialized: agentId={}, conversationId={}", agentId, conversationId);
            }
        } catch (Exception e) {
            log.warn("[TalkMode] Invalid text message: {}", e.getMessage());
            sendJson(session, Map.of("type", "error", "message", "Invalid message format"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        TalkSession talkSession = sessions.get(session.getId());
        if (talkSession == null) {
            try {
                sendJson(session, Map.of("type", "error", "message", "Session not initialized. Send init message first."));
            } catch (IOException e) {
                log.warn("[TalkMode] Failed to send error: {}", e.getMessage());
            }
            return;
        }

        byte[] audioData = message.getPayload().array();
        log.info("[TalkMode] Received audio: {} bytes", audioData.length);

        // 异步处理：STT -> Agent -> TTS
        executor.execute(() -> processAudio(session, talkSession, audioData));
    }

    private void processAudio(WebSocketSession session, TalkSession talkSession, byte[] audioData) {
        try {
            // 1. 通知前端进入处理状态
            sendJson(session, Map.of("type", "state", "state", "processing"));

            // 2. STT: 音频转文字
            // 前端用 WavRecorder（Web Audio API + 手写 PCM WAV 编码）— 见
            // mateclaw-ui/src/utils/wavEncoder.ts. WebM/Opus 被 DashScope
            // Paraformer 拒收，WAV 是所有 STT provider 都接受的最大公约数。
            Map<String, Object> sttResult = sttService.transcribe(audioData, "audio.wav", "audio/wav", null);
            if (!Boolean.TRUE.equals(sttResult.get("success"))) {
                sendJson(session, Map.of("type", "error", "message", "Speech recognition failed: " + sttResult.get("error")));
                sendJson(session, Map.of("type", "state", "state", "idle"));
                return;
            }

            String transcript = (String) sttResult.get("text");
            if (transcript == null || transcript.isBlank()) {
                sendJson(session, Map.of("type", "state", "state", "idle"));
                return;
            }

            // 3. 推送转写结果
            sendJson(session, Map.of("type", "transcript", "text", transcript));

            // 4. 保存用户消息（workspace 从 agent 获取）
            var talkAgent = agentService.getAgent(talkSession.agentId);
            Long talkWsId = talkAgent != null ? talkAgent.getWorkspaceId() : 1L;
            conversationService.getOrCreateConversation(
                    talkSession.conversationId, talkSession.agentId, talkSession.username, talkWsId);
            conversationService.saveMessage(talkSession.conversationId, "user", transcript, List.of());

            // 5. Agent 对话（同步）
            AgentService.ChatResult chatResult = agentService.chatWithUsage(
                    talkSession.agentId, transcript, talkSession.conversationId);
            String reply = chatResult.content();
            if (reply == null || reply.isBlank()) {
                reply = "Sorry, I couldn't generate a response.";
            }

            // 6. 保存助手回复（携带 token usage + runtime model 归属）
            conversationService.saveMessage(talkSession.conversationId, "assistant", reply, List.of(),
                    "completed", chatResult.promptTokens(), chatResult.completionTokens(),
                    chatResult.runtimeModel(), chatResult.runtimeProvider());

            // Publish conversation-completed event so memory extraction runs for voice turns too.
            completionPublisher.publish(talkSession.agentId, talkSession.conversationId,
                    transcript, reply, "talk");

            // 7. 推送文字回复
            sendJson(session, Map.of("type", "reply", "text", reply));

            // 8. TTS: 文字转语音
            sendJson(session, Map.of("type", "state", "state", "speaking"));
            Map<String, Object> ttsResult = ttsService.synthesize(
                    talkSession.conversationId, reply, null, null, null);

            if (Boolean.TRUE.equals(ttsResult.get("success"))) {
                String audioUrl = (String) ttsResult.get("audioUrl");
                if (audioUrl != null) {
                    // 读取音频文件并通过 WebSocket 发送
                    Path audioPath = Paths.get(audioUrl);
                    if (!audioPath.isAbsolute()) {
                        audioPath = Paths.get("data", "tts-output").resolve(audioUrl);
                    }
                    if (Files.exists(audioPath)) {
                        byte[] audioBytes = Files.readAllBytes(audioPath);
                        session.sendMessage(new BinaryMessage(audioBytes));
                        log.info("[TalkMode] Sent TTS audio: {} bytes", audioBytes.length);
                    } else {
                        // 回退：发送音频 URL 让前端直接播放
                        sendJson(session, Map.of("type", "tts_url", "url", audioUrl));
                    }
                }
            } else {
                log.warn("[TalkMode] TTS failed: {}", ttsResult.get("error"));
            }

            // 9. 完成，回到空闲状态
            sendJson(session, Map.of("type", "state", "state", "idle"));

        } catch (Exception e) {
            log.error("[TalkMode] Error processing audio: {}", e.getMessage(), e);
            try {
                sendJson(session, Map.of("type", "error", "message", e.getMessage()));
                sendJson(session, Map.of("type", "state", "state", "idle"));
            } catch (IOException ex) {
                log.warn("[TalkMode] Failed to send error: {}", ex.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("[TalkMode] WebSocket disconnected: {} (status={})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session.getId());
        log.warn("[TalkMode] Transport error: {} - {}", session.getId(), exception.getMessage());
    }

    private void sendJson(WebSocketSession session, Map<String, Object> data) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
        }
    }
}
