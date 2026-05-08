package vip.mate.tool.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.task.AsyncTaskService;
import vip.mate.task.AsyncTaskService.TaskPollResult;
import vip.mate.task.model.AsyncTaskEntity;
import vip.mate.task.model.AsyncTaskInfo;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片生成服务 — 统一入口，处理 provider 选择、参数归一化、fallback、同步/异步提交
 * <p>
 * 与 VideoGenerationService 结构一致，额外处理同步模式（部分 Provider 直接返回图片 URL）。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final SystemSettingService systemSettingService;
    private final ImageProviderRegistry providerRegistry;
    private final AsyncTaskService asyncTaskService;
    private final ConversationService conversationService;
    private final ImageFileDownloader fileDownloader;
    private final ObjectMapper objectMapper;
    private final ChatStreamTracker streamTracker;

    private static final String TASK_TYPE = "image_generation";

    /**
     * 提交图片生成任务
     */
    public ImageGenerationResult submitGeneration(ImageGenerationRequest request,
                                                    String conversationId,
                                                    String createdBy) {
        SystemSettingsDTO config = systemSettingService.getAllSettings();

        // 1. 检查图片功能是否启用
        if (!Boolean.TRUE.equals(config.getImageEnabled())) {
            return ImageGenerationResult.failure("图片生成功能未启用，请在系统设置中开启");
        }

        // 2. 模式推断
        if (request.getMode() == null) {
            request.setMode(inferMode(request));
        }

        // 3. Provider 选择
        ImageProviderRegistry.ResolvedProvider resolved =
                providerRegistry.resolve(config, request.getMode());
        if (resolved == null) {
            return ImageGenerationResult.failure(
                    "没有可用的图片生成 Provider，请在系统设置中配置（支持 DashScope、OpenAI、fal.ai、智谱）");
        }

        // 4. 提交（含 fallback）
        return submitWithFallback(request, config, resolved, conversationId, createdBy);
    }

    /**
     * 查询任务状态
     */
    public AsyncTaskInfo checkTaskStatus(String taskId) {
        return asyncTaskService.getTaskInfo(taskId);
    }

    // ==================== 内部逻辑 ====================

    private ImageGenerationResult submitWithFallback(ImageGenerationRequest request,
                                                       SystemSettingsDTO config,
                                                       ImageProviderRegistry.ResolvedProvider primary,
                                                       String conversationId,
                                                       String createdBy) {
        // 尝试 primary
        normalizeForProvider(request, primary.provider());
        ImageSubmitResult submitResult = primary.provider().submit(request, config);
        if (submitResult.isAccepted()) {
            return handleSubmitResult(submitResult, request, conversationId, createdBy, config);
        }

        // Fallback
        List<String> attemptErrors = new ArrayList<>();
        attemptErrors.add(primary.provider().id() + ": " + submitResult.getErrorMessage());

        if (Boolean.TRUE.equals(config.getImageFallbackEnabled())) {
            List<ImageGenerationProvider> fallbacks =
                    providerRegistry.fallbackCandidates(config, request.getMode(), primary.provider().id());
            for (ImageGenerationProvider fb : fallbacks) {
                log.info("[ImageGen] Trying fallback provider: {}", fb.id());
                normalizeForProvider(request, fb);
                submitResult = fb.submit(request, config);
                if (submitResult.isAccepted()) {
                    return handleSubmitResult(submitResult, request, conversationId, createdBy, config);
                }
                attemptErrors.add(fb.id() + ": " + submitResult.getErrorMessage());
                log.warn("[ImageGen] Fallback provider {} failed: {}", fb.id(), submitResult.getErrorMessage());
            }
        }

        return ImageGenerationResult.failure(
                "所有 Provider 均提交失败\n" + String.join("\n", attemptErrors));
    }

    private ImageGenerationResult handleSubmitResult(ImageSubmitResult submitResult,
                                                       ImageGenerationRequest request,
                                                       String conversationId,
                                                       String createdBy,
                                                       SystemSettingsDTO config) {
        if (submitResult.isAsync()) {
            // 异步模式：创建任务 + 启动轮询
            return createAsyncTask(submitResult, request, conversationId, createdBy, config);
        } else {
            // 同步模式：直接下载图片、保存消息
            return handleSyncCompletion(submitResult, conversationId, createdBy);
        }
    }

    private ImageGenerationResult createAsyncTask(ImageSubmitResult submitResult,
                                                    ImageGenerationRequest request,
                                                    String conversationId,
                                                    String createdBy,
                                                    SystemSettingsDTO config) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            AsyncTaskEntity task = asyncTaskService.createTask(
                    TASK_TYPE, conversationId, null,
                    submitResult.getProviderName(),
                    submitResult.getProviderTaskId(),
                    requestJson, createdBy);

            ImageGenerationProvider provider = providerRegistry.getById(submitResult.getProviderName());
            if (provider == null) {
                return ImageGenerationResult.failure("Provider 不存在: " + submitResult.getProviderName());
            }

            asyncTaskService.startPolling(
                    task.getTaskId(),
                    providerTaskId -> provider.checkStatus(providerTaskId, systemSettingService.getAllSettings()),
                    (completedTask, pollResult) -> handleAsyncCompletion(completedTask, pollResult)
            );

            return ImageGenerationResult.asyncSuccess(task.getTaskId(), submitResult.getProviderName());
        } catch (Exception e) {
            log.error("[ImageGen] Failed to create async task: {}", e.getMessage(), e);
            return ImageGenerationResult.failure("创建任务失败: " + e.getMessage());
        }
    }

    /**
     * 同步 Provider 完成后：下载图片 → 保存消息
     */
    private ImageGenerationResult handleSyncCompletion(ImageSubmitResult submitResult,
                                                         String conversationId,
                                                         String createdBy) {
        try {
            List<String> imageUrls = submitResult.getImageUrls();
            List<String> servingUrls = new ArrayList<>();
            String taskId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            List<MessageContentPart> contentParts = new ArrayList<>();
            for (int i = 0; i < imageUrls.size(); i++) {
                Path localPath = fileDownloader.download(imageUrls.get(i), conversationId, taskId, i);
                String servingUrl = fileDownloader.toServingUrl(conversationId, localPath);
                servingUrls.add(servingUrl);

                MessageContentPart imagePart = MessageContentPart.image(null, servingUrl);
                imagePart.setFileName(localPath.getFileName().toString());
                imagePart.setContentType("image/png");
                contentParts.add(imagePart);
            }

            // 保存 assistant 消息
            conversationService.saveMessage(
                    conversationId, "assistant",
                    "图片已生成完毕",
                    contentParts, "completed");

            // Broadcast async_task_completed so the chat window renders the image
            // inline immediately. Without this, the message is in DB but the SSE
            // stream never tells the frontend a new assistant turn arrived, so the
            // user's "loading…" spinner stays until they refresh and the
            // conversation re-fetches from DB. Mirror the async path's payload
            // shape (taskId / taskType / success / imageUrl) so the existing
            // frontend handler treats it identically.
            for (String servingUrl : servingUrls) {
                Map<String, Object> data = new HashMap<>();
                data.put("taskId", taskId);
                data.put("taskType", TASK_TYPE);
                data.put("success", true);
                data.put("imageUrl", servingUrl);
                data.put("providerName", submitResult.getProviderName());
                streamTracker.broadcastObject(conversationId, "async_task_completed", data);
            }

            log.info("[ImageGen] Sync generation completed, {} image(s) saved for conversation {}",
                    servingUrls.size(), conversationId);

            return ImageGenerationResult.syncSuccess(submitResult.getProviderName(), servingUrls);
        } catch (Exception e) {
            log.error("[ImageGen] Sync completion handling failed: {}", e.getMessage(), e);
            return ImageGenerationResult.failure("图片下载或保存失败: " + e.getMessage());
        }
    }

    /**
     * 异步任务完成时的回写逻辑：下载图片 → 保存消息 → 广播 SSE
     */
    private void handleAsyncCompletion(AsyncTaskEntity task, TaskPollResult result) {
        // The conversation may have been deleted while the poller was running.
        // Gate every post-completion side effect — file write, message save,
        // success/failure broadcast — so we never write to a tombstoned
        // conversation regardless of which sub-branch we'd take.
        if (asyncTaskService.isConversationCanceled(task.getConversationId())) {
            log.info("[ImageGen] Task {} (success={}) aborted: conversation {} was deleted",
                    task.getTaskId(), result.succeeded(), task.getConversationId());
            return;
        }

        if (result.succeeded()) {
            try {
                String imageUrl = result.imageUrl();
                if (imageUrl == null) {
                    log.warn("[ImageGen] Task {} succeeded but no image URL", task.getTaskId());
                    asyncTaskService.broadcastTaskEvent(task, "async_task_completed",
                            false, null, null, "图片生成成功但未返回图片 URL");
                    return;
                }

                // 下载图片到本地
                Path localPath = fileDownloader.download(imageUrl, task.getConversationId(), task.getTaskId(), 0);
                String servingUrl = fileDownloader.toServingUrl(task.getConversationId(), localPath);

                // 保存 assistant 消息
                MessageContentPart imagePart = MessageContentPart.image(null, servingUrl);
                imagePart.setFileName(localPath.getFileName().toString());
                imagePart.setContentType("image/png");

                conversationService.saveMessage(
                        task.getConversationId(), "assistant",
                        "图片已生成完毕",
                        List.of(imagePart), "completed");

                // SSE 广播（使用 imageUrl 字段）
                asyncTaskService.broadcastTaskEvent(task, "async_task_completed",
                        true, null, servingUrl, null);

                log.info("[ImageGen] Task {} completed, image saved: {}", task.getTaskId(), servingUrl);
            } catch (Exception e) {
                log.error("[ImageGen] Completion handling failed for task {}: {}",
                        task.getTaskId(), e.getMessage(), e);
                if (asyncTaskService.isConversationCanceled(task.getConversationId())) {
                    log.info("[ImageGen] Skipping failure broadcast for deleted conversation {}",
                            task.getConversationId());
                    return;
                }
                asyncTaskService.broadcastTaskEvent(task, "async_task_completed",
                        false, null, null, "图片下载或保存失败: " + e.getMessage());
            }
        } else {
            asyncTaskService.broadcastTaskEvent(task, "async_task_completed",
                    false, null, null, result.errorMessage());
            log.warn("[ImageGen] Task {} failed: {}", task.getTaskId(), result.errorMessage());
        }
    }

    private ImageCapability inferMode(ImageGenerationRequest request) {
        if (request.getInputImages() != null && !request.getInputImages().isEmpty()) {
            return ImageCapability.IMAGE_EDIT;
        }
        return ImageCapability.TEXT_TO_IMAGE;
    }

    private void normalizeForProvider(ImageGenerationRequest request, ImageGenerationProvider provider) {
        ImageProviderCapabilities caps = provider.detailedCapabilities();
        if (caps == null) return;

        // Resolve aspect ratio first so size normalization can preserve orientation.
        String aspectRatio = caps.normalizeAspectRatio(request.getAspectRatio());
        request.setAspectRatio(aspectRatio);
        request.setSize(caps.normalizeSize(request.getSize(), aspectRatio));
        if (request.getCount() != null) {
            request.setCount(caps.normalizeCount(request.getCount()));
        }
    }
}
