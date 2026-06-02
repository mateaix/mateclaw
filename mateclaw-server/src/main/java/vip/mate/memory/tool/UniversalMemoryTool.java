package vip.mate.memory.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.event.MemoryWriteEvent;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.workspace.document.model.WorkspaceFileEntity;
import vip.mate.workspace.document.WorkspaceFileService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * RFC-090 §11.3.1 — universal {@code remember()} tool.
 *
 * <p>{@link StructuredMemoryTool} forces a typed schema (user/feedback/
 * project/reference). That's the right primitive for stable knowledge,
 * but skills also produce free-form lessons that don't fit those
 * buckets. This tool gives the agent a single, parameter-light way to
 * leave a note for the next conversation.
 *
 * <p>Storage path: appends to {@code MEMORY.md} under a
 * {@code ## Recent Lessons} section so the next dream pass can
 * consolidate it. {@link MemoryWriteEvent} is published on success so
 * the existing SOUL summarizer K-counter advances.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UniversalMemoryTool {

    private static final String MEMORY_FILENAME = "MEMORY.md";
    private static final String LESSONS_HEADER = "## Recent Lessons";
    private static final DateTimeFormatter ENTRY_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm");

    private final WorkspaceFileService workspaceFileService;
    private final ApplicationEventPublisher eventPublisher;
    private final MemoryOwnerResolver memoryOwnerResolver;
    private final MemoryProperties memoryProperties;

    @Tool(description = """
            将一条自由形式的经验或洞察追加到 Agent 的长期记忆 (MEMORY.md)。
            适用于不属于结构化 4 类 (user/feedback/project/reference) 的自由笔记。
            内容会被下一次 Dream consolidation 整合到 SOUL.md 或事实区。
            如果你需要记录的是结构化条目，优先用 remember_structured。
            """)
    public String remember(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "要记住的内容（自由形式）") String content,
            @ToolParam(description = "可选：来源上下文（skill 名 / conversation id）", required = false) String source,
            ToolContext toolContext) {

        if (agentId == null) return error("agentId 不能为空");
        if (content == null || content.isBlank()) return error("content 不能为空");

        try {
            // Write to the requester's PERSONAL MEMORY.md when per-owner isolation
            // is active; otherwise the shared file (so the note is not stranded
            // in an un-read PERSONAL row).
            String ownerKey = memoryProperties.isLifecycleMediatorEnabled()
                    ? memoryOwnerResolver.resolve(ChatOrigin.from(toolContext))
                    : null;
            WorkspaceFileEntity existing = workspaceFileService.getVisibleFile(agentId, MEMORY_FILENAME, ownerKey);
            String existingContent = existing != null && existing.getContent() != null
                    ? existing.getContent() : "";
            String updated = appendLesson(existingContent, content, source);
            workspaceFileService.saveVisibleFile(agentId, MEMORY_FILENAME, updated, ownerKey);

            // RFC-090 §14.3 — universal remember() targets MEMORY.md (the
            // canonical file), so this IS a MemoryWriteEvent. Skill-local
            // lessons go through SkillLessonWrittenEvent instead and do
            // NOT touch this path.
            eventPublisher.publishEvent(new MemoryWriteEvent(agentId, MEMORY_FILENAME,
                    "remember", content));

            JSONObject result = new JSONObject();
            result.set("success", true);
            result.set("file", MEMORY_FILENAME);
            result.set("message", "已记入 MEMORY.md，将在下次 Dream consolidation 时整合");
            return JSONUtil.toJsonPrettyStr(result);
        } catch (Exception e) {
            log.warn("[UniversalMemoryTool] remember failed: {}", e.getMessage());
            return error("记忆写入失败: " + e.getMessage());
        }
    }

    /**
     * Append the lesson under {@value LESSONS_HEADER}, preserving any
     * existing MEMORY.md content. Creates the section header on first
     * write.
     */
    static String appendLesson(String existing, String content, String source) {
        StringBuilder sb = new StringBuilder();
        String ts = LocalDateTime.now().format(ENTRY_TS);
        String entry = "- " + ts
                + (source != null && !source.isBlank() ? " (" + source.trim() + ")" : "")
                + ": " + content.trim();

        if (existing == null || existing.isBlank()) {
            sb.append(LESSONS_HEADER).append("\n").append(entry).append("\n");
            return sb.toString();
        }

        int headerIdx = existing.indexOf(LESSONS_HEADER);
        if (headerIdx < 0) {
            sb.append(existing);
            if (!existing.endsWith("\n")) sb.append("\n");
            sb.append("\n").append(LESSONS_HEADER).append("\n").append(entry).append("\n");
            return sb.toString();
        }

        // Insert at the end of the Recent Lessons section (before the
        // next ##-level heading or EOF).
        int sectionStart = headerIdx + LESSONS_HEADER.length();
        int nextHeading = findNextHeading(existing, sectionStart);
        if (nextHeading < 0) {
            sb.append(existing);
            if (!existing.endsWith("\n")) sb.append("\n");
            sb.append(entry).append("\n");
        } else {
            sb.append(existing, 0, nextHeading);
            if (!existing.substring(0, nextHeading).endsWith("\n")) sb.append("\n");
            sb.append(entry).append("\n\n");
            sb.append(existing, nextHeading, existing.length());
        }
        return sb.toString();
    }

    private static int findNextHeading(String content, int from) {
        // Match a line starting with "## " (any heading level >= 2).
        int idx = content.indexOf("\n## ", from);
        return idx < 0 ? -1 : idx + 1; // position of '#' itself
    }

    private static String error(String msg) {
        JSONObject e = new JSONObject();
        e.set("success", false);
        e.set("error", msg);
        return JSONUtil.toJsonPrettyStr(e);
    }
}
