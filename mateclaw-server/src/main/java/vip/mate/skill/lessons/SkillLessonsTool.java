package vip.mate.skill.lessons;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

/**
 * RFC-090 §11.4.2 — agent-callable {@code record_lesson} tool.
 *
 * <p>The tool is always exposed by the registry; the per-skill
 * {@code self-evolution.lessons_enabled} switch governs whether the
 * skill's manifest opts in. We don't filter at registry time so
 * skills can dynamically toggle the flag without a rebuild. When the
 * skill says no, the call returns a friendly explanation rather than
 * silently writing.
 *
 * <p>Storage and event publishing live in
 * {@link SkillLessonsService#recordLesson}. This class is the LLM-
 * facing wrapper.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillLessonsTool {

    private final SkillRuntimeService skillRuntimeService;
    private final SkillLessonsService lessonsService;

    @Tool(description = """
            为指定 skill 记录一条 lesson（经验/洞察），追加到该 skill 的 LESSONS.md。
            下次加载该 skill 时，LESSONS.md 内容会自动注入到 system prompt。
            仅当 skill manifest 里 self-evolution.lessons_enabled=true 时生效（默认开启）。
            如果想记录的内容跨 skill 通用，请改用 remember 或 remember_structured。
            """)
    public String record_lesson(
            @ToolParam(description = "skill 的 slug（即 SKILL.md frontmatter 里的 name）") String skillName,
            @ToolParam(description = "要记录的经验内容") String lesson,
            @ToolParam(description = "可选：当前 Agent 的 ID。传入时必须使用字符串，避免大整数精度丢失", required = false) String agentId,
            @ToolParam(description = "可选：当前对话 ID", required = false) String conversationId) {

        if (skillName == null || skillName.isBlank()) return error("skillName 不能为空");
        if (lesson == null || lesson.isBlank()) return error("lesson 不能为空");
        Long parsedAgentId;
        try {
            parsedAgentId = parseOptionalAgentId(agentId);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        ResolvedSkill resolved = skillRuntimeService.resolveAllSkillsStatus().stream()
                .filter(s -> s != null && skillName.equals(s.getName()))
                .findFirst()
                .orElse(null);
        if (resolved == null) {
            return error("找不到 skill: " + skillName);
        }

        SkillManifest manifest = resolved.getManifest();
        // §10.2 Q7 — default ON; only skip when the manifest explicitly opts out.
        boolean lessonsEnabled = manifest == null
                || manifest.getSelfEvolution() == null
                || manifest.getSelfEvolution().isLessonsEnabled();
        if (!lessonsEnabled) {
            return error("该 skill 已在 manifest 中关闭 self-evolution.lessons_enabled，"
                    + "无法记录 lesson。请改用 remember 工具或修改 manifest。");
        }

        int max = manifest != null && manifest.getSelfEvolution() != null
                ? manifest.getSelfEvolution().getLessonsMaxEntries() : 0;

        String lessonId = lessonsService.recordLesson(resolved, parsedAgentId, conversationId,
                lesson, max);
        if (lessonId == null) {
            return error("Lesson 记录失败：skill 可能仅存在于数据库（无 workspace 目录）。");
        }

        JSONObject result = new JSONObject();
        result.set("success", true);
        result.set("skill", skillName);
        result.set("lessonId", lessonId);
        result.set("message", "Lesson 已记录，下次加载该 skill 时会自动注入到 system prompt。");
        return JSONUtil.toJsonPrettyStr(result);
    }

    private static Long parseOptionalAgentId(String agentId) {
        String trimmed = agentId != null ? agentId.trim() : "";
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("agentId 必须是数字字符串");
        }
    }

    private static String error(String msg) {
        JSONObject e = new JSONObject();
        e.set("success", false);
        e.set("error", msg);
        return JSONUtil.toJsonPrettyStr(e);
    }
}
