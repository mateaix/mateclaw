package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.routing.MediaCaptionService;
import vip.mate.llm.routing.MultimodalRouter;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

/**
 * On-demand image analysis tool.
 *
 * <p>When the agent's primary model is text-only, images uploaded earlier in the
 * conversation are only captioned generically once (by the automatic vision
 * sidecar) and that caption is frozen into history. This tool lets the agent
 * re-examine a previously uploaded image against the user's actual follow-up
 * question — passing the question to the configured vision model so the answer is
 * tailored rather than a stale generic description.
 *
 * <p>It resolves the target image from the current conversation: an explicit
 * filename/path reference, or the most recent image when none is given. It reuses
 * the same vision model the automatic sidecar uses, so behaviour stays consistent
 * across the automatic and on-demand paths.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageAnalyzeTool {

    private final ConversationService conversationService;
    private final MultimodalRouter multimodalRouter;
    private final MediaCaptionService mediaCaptionService;

    @Tool(description = "Analyze an image the user uploaded earlier in this conversation, answering a specific "
            + "question about it. Use this whenever the user asks a follow-up about a previously sent image "
            + "(e.g. 'what does the error in that screenshot say', 'read the total on the receipt') and the "
            + "current model cannot see images natively. By default it analyzes the most recent image; pass "
            + "'image' to target a specific one by filename. Returns the vision model's answer as text.")
    public String image_analyze(
            @ToolParam(description = "The specific question to answer about the image, in the user's own words. "
                    + "Be concrete — e.g. 'What is the error message?' rather than 'describe it'.") String question,
            @ToolParam(description = "Optional filename or path of the target image. Omit to use the most recent "
                    + "image in the conversation.", required = false) String image,
            @Nullable ToolContext ctx
    ) {
        if (question == null || question.isBlank()) {
            return "请提供要针对图片回答的具体问题。";
        }

        String conversationId = ToolExecutionContext.conversationId(ctx);
        if (conversationId == null || conversationId.isBlank()) {
            return "无法确定当前会话，无法定位已上传的图片。";
        }

        MessageContentPart target = findImagePart(conversationId, image);
        if (target == null) {
            return image == null || image.isBlank()
                    ? "本次会话中没有找到可分析的图片，请确认用户已上传图片。"
                    : "未找到名为「" + image + "」的图片，请检查文件名，或省略该参数以分析最近的图片。";
        }

        ModelConfigEntity visionModel = multimodalRouter.resolveVisionSidecar();
        if (visionModel == null) {
            return "尚未配置视觉模型，无法分析图片。请在「设置 → 模型」中将一个具备视觉能力的模型设为默认视觉模型。";
        }

        // Locale null → caption service answers in the question's own language.
        MediaCaptionService.CaptionResult result =
                mediaCaptionService.caption(visionModel, target, null, question);
        if (result.isFailure()) {
            log.warn("[image_analyze] caption failed for {} via {}/{}: {}",
                    target.getFileName(), visionModel.getProvider(), visionModel.getModelName(),
                    result.failure() == null ? "unknown" : result.failure().getMessage());
            return "视觉模型未能解析该图片（" + safeName(target) + "），请稍后重试或检查视觉模型配置。";
        }
        return result.description();
    }

    /**
     * Resolve the target image part from the conversation. With a reference,
     * matches by filename basename / path / mediaId suffix, scanning newest-first.
     * Without one, returns the most recent image part.
     */
    private MessageContentPart findImagePart(String conversationId, String reference) {
        List<MessageEntity> messages;
        try {
            messages = conversationService.listMessages(conversationId);
        } catch (Exception e) {
            log.warn("[image_analyze] failed to load messages for {}: {}", conversationId, e.getMessage());
            return null;
        }
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        String ref = reference == null ? null : reference.trim();
        boolean wildcard = ref == null || ref.isBlank()
                || ref.equalsIgnoreCase("latest") || ref.equalsIgnoreCase("last");
        for (int i = messages.size() - 1; i >= 0; i--) {
            List<MessageContentPart> parts = conversationService.parseMessageParts(messages.get(i));
            for (int j = parts.size() - 1; j >= 0; j--) {
                MessageContentPart part = parts.get(j);
                if (!isImage(part)) continue;
                if (wildcard || matchesReference(part, ref)) {
                    return part;
                }
            }
        }
        return null;
    }

    private boolean isImage(MessageContentPart part) {
        if (part == null) return false;
        String type = part.getType();
        String contentType = part.getContentType();
        boolean image = "image".equals(type)
                || ("file".equals(type) && contentType != null && contentType.startsWith("image/"));
        return image && !(contentType != null && contentType.contains("svg"));
    }

    private boolean matchesReference(MessageContentPart part, String ref) {
        if (ref == null) return false;
        String basename = ref.contains("/") || ref.contains("\\")
                ? ref.substring(Math.max(ref.lastIndexOf('/'), ref.lastIndexOf('\\')) + 1)
                : ref;
        return endsWithIgnoreCase(part.getFileName(), basename)
                || endsWithIgnoreCase(part.getPath(), ref)
                || endsWithIgnoreCase(part.getMediaId(), ref);
    }

    private boolean endsWithIgnoreCase(String value, String suffix) {
        if (value == null || suffix == null || suffix.isBlank()) return false;
        return value.toLowerCase().endsWith(suffix.toLowerCase());
    }

    private String safeName(MessageContentPart part) {
        String name = part.getFileName();
        return name == null || name.isBlank() ? "image" : name;
    }
}
