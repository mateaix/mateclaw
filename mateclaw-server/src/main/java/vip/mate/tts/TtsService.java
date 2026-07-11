package vip.mate.tts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TTS 语音合成服务 — 核心编排，处理 provider 选择、文本预处理、fallback、文件保存
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {

    private final SystemSettingService systemSettingService;
    private final TtsProviderRegistry providerRegistry;
    private final ChatStreamTracker streamTracker;
    private final ChatUploadLocationResolver uploadLocationResolver;

    private static final int MAX_TEXT_LENGTH = 4096;

    /** 用于自动 TTS 的异步线程池 */
    private final ExecutorService ttsExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "tts-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * 合成语音并保存为文件
     *
     * @return { success, audioUrl, contentType, providerName }
     */
    public Map<String, Object> synthesize(String conversationId, String text,
                                            String voice, Double speed, String format) {
        SystemSettingsDTO config = systemSettingService.getAllSettings();

        if (!Boolean.TRUE.equals(config.getTtsEnabled())) {
            return Map.of("success", false, "error", "TTS 功能未启用，请在系统设置中开启");
        }

        // 文本预处理
        String cleanText = preprocessText(text);
        if (cleanText.isBlank()) {
            return Map.of("success", false, "error", "待合成的文本为空");
        }

        // 构建请求
        TtsRequest request = TtsRequest.builder()
                .text(cleanText)
                .voice(voice)
                .speed(speed != null ? speed : config.getTtsSpeed())
                .format(format != null ? format : "mp3")
                .build();

        // Provider 选择 + fallback
        TtsResult result = synthesizeWithFallback(request, config);
        if (!result.isSuccess()) {
            return Map.of("success", false, "error", result.getErrorMessage());
        }

        // 保存文件
        try {
            String fileId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Path filePath = saveAudioFile(conversationId, fileId, result.getAudioData(), result.getFormat());
            String audioUrl = "/api/v1/chat/files/" + conversationId + "/" + filePath.getFileName();

            return Map.of(
                    "success", true,
                    "audioUrl", audioUrl,
                    "contentType", result.getContentType(),
                    "format", result.getFormat()
            );
        } catch (IOException e) {
            log.error("[TTS] Failed to save audio file: {}", e.getMessage(), e);
            return Map.of("success", false, "error", "音频文件保存失败: " + e.getMessage());
        }
    }

    /**
     * 自动 TTS：消息完成后异步触发，通过 SSE 广播结果
     */
    public void autoSynthesize(String conversationId, String text) {
        ttsExecutor.submit(() -> {
            try {
                Map<String, Object> result = synthesize(conversationId, text, null, null, null);
                if (Boolean.TRUE.equals(result.get("success"))) {
                    Map<String, Object> event = new HashMap<>();
                    event.put("audioUrl", result.get("audioUrl"));
                    event.put("contentType", result.get("contentType"));
                    streamTracker.broadcastObject(conversationId, "tts_ready", event);
                    log.info("[TTS] Auto-synthesized for conversation {}", conversationId);
                }
            } catch (Exception e) {
                log.error("[TTS] Auto-synthesize failed: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 检查 TTS 功能是否全局启用（供 ChannelMessageRouter 等外部组件调用）
     */
    public boolean isTtsEnabled() {
        SystemSettingsDTO config = systemSettingService.getSettings();
        return Boolean.TRUE.equals(config.getTtsEnabled());
    }

    /**
     * 获取当前配置是否开启自动 TTS
     */
    public boolean isAutoModeEnabled() {
        SystemSettingsDTO config = systemSettingService.getSettings();
        return Boolean.TRUE.equals(config.getTtsEnabled())
                && "always".equals(config.getTtsAutoMode());
    }

    /**
     * 列出所有可用的语音
     */
    public List<Map<String, Object>> listVoices() {
        SystemSettingsDTO config = systemSettingService.getAllSettings();
        List<Map<String, Object>> voices = new ArrayList<>();

        for (TtsProvider provider : providerRegistry.allSorted()) {
            boolean available = provider.isAvailable(config);
            for (String voice : provider.availableVoices()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("voice", voice);
                info.put("provider", provider.id());
                info.put("providerLabel", provider.label());
                info.put("available", available);
                info.put("isDefault", voice.equals(provider.defaultVoice()));
                voices.add(info);
            }
        }
        return voices;
    }

    // ==================== 内部逻辑 ====================

    private TtsResult synthesizeWithFallback(TtsRequest request, SystemSettingsDTO config) {
        TtsProvider primary = providerRegistry.resolve(config);
        if (primary == null) {
            return TtsResult.failure("没有可用的 TTS Provider，请检查配置");
        }

        TtsResult result = primary.synthesize(request, config);
        if (result.isSuccess()) {
            return result;
        }

        // Fallback
        List<String> errors = new ArrayList<>();
        errors.add(primary.id() + ": " + result.getErrorMessage());

        if (Boolean.TRUE.equals(config.getTtsFallbackEnabled())) {
            for (TtsProvider fb : providerRegistry.fallbackCandidates(config, primary.id())) {
                log.info("[TTS] Trying fallback provider: {}", fb.id());
                result = fb.synthesize(request, config);
                if (result.isSuccess()) {
                    return result;
                }
                errors.add(fb.id() + ": " + result.getErrorMessage());
            }
        }

        return TtsResult.failure("所有 TTS Provider 均失败\n" + String.join("\n", errors));
    }

    private String preprocessText(String text) {
        if (text == null) return "";
        // 去除 Markdown 格式
        String clean = text
                .replaceAll("```[\\s\\S]*?```", "") // 代码块
                .replaceAll("`[^`]+`", "")           // 行内代码
                .replaceAll("!?\\[([^\\]]*)\\]\\([^)]+\\)", "$1") // 链接/图片
                .replaceAll("[*_~]{1,3}", "")         // 加粗/斜体/删除线
                .replaceAll("^#{1,6}\\s+", "")        // 标题
                .replaceAll("^[\\-*+]\\s+", "")       // 列表
                .replaceAll("^>\\s+", "")             // 引用
                .replaceAll("\\|[^|]+\\|", "")        // 表格
                .replaceAll("\n{3,}", "\n\n")          // 多余空行
                .trim();

        // 截断
        if (clean.length() > MAX_TEXT_LENGTH) {
            clean = clean.substring(0, MAX_TEXT_LENGTH);
        }
        return clean;
    }

    private Path saveAudioFile(String conversationId, String fileId, byte[] data, String format)
            throws IOException {
        Path dir = uploadLocationResolver.resolveConversationDir(conversationId);
        Files.createDirectories(dir);
        String fileName = "tts_" + fileId + "." + format;
        Path filePath = dir.resolve(fileName);
        Files.write(filePath, data);
        return filePath;
    }
}
