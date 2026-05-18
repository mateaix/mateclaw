package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.SkillSecurityService;
import vip.mate.skill.runtime.SkillValidationResult;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.util.regex.Pattern;

/**
 * RFC-023: Agent 自治 Skill 管理工具
 * <p>
 * 对标 hermes-agent 的 skill_manager_tool.py，让 Agent 在对话中自主创建、编辑、
 * 修补和删除 Skill。每次写入前强制安全扫描，失败则拒绝并返回原因。
 * <p>
 * 系统 prompt 引导 Agent 使用此工具：
 * <blockquote>
 * "After completing a complex task (5+ tool calls), fixing a tricky error,
 * or discovering a non-trivial workflow, save the approach as a skill using
 * skill_manage so you can reuse it next time."
 * </blockquote>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillManageTool {

    private final SkillService skillService;
    private final SkillSecurityService securityService;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillRuntimeService runtimeService;

    /** Skill 名称格式：小写字母/数字/连字符/下划线/点，首字符必须是字母或数字 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    /** Skill 内容最大长度（~25K tokens） */
    private static final int MAX_CONTENT_CHARS = 100_000;

    @vip.mate.tool.ConcurrencyUnsafe("create/edit/patch/delete on the shared skill registry; concurrent ops on the same skill name race")
    @Tool(description = """
        Manage the canonical SKILL.md content for a reusable skill: create, edit, patch,
        or delete the skill itself (its body, version, description, frontmatter).

        USE THIS TOOL when the user (or you) wants to change WHAT a skill is:
        - Create a new skill from scratch
        - Rewrite an existing skill's body or steps
        - Bump the version field in YAML frontmatter
        - Fix a typo, outdated command, or wrong instruction inside SKILL.md
        - Delete a skill

        DO NOT use this tool to record a tip, observation, or lesson learned while USING
        a skill — that belongs in record_lesson (per-skill LESSONS.md) or remember
        (cross-skill memory). Lessons are notes ABOUT a skill; this tool rewrites the
        skill itself.

        Quick rule of thumb:
        - "Update / fix / rewrite / change version of skill X"  → skill_manage
        - "Remember that X works better when..." / "Note: ..."  → record_lesson or remember

        When to create a skill:
        - After completing a complex task (5+ tool calls)
        - After fixing a tricky error with a non-obvious solution
        - After discovering a workflow worth remembering

        When to patch a skill:
        - When using a skill and finding it outdated, incomplete, or wrong
        - Don't wait to be asked — patch immediately

        Actions:
        - create: Create a new skill with SKILL.md content (YAML frontmatter + markdown body)
        - edit: Replace entire skill content (for major rewrites; preferred when changing version + body together)
        - patch: Find-and-replace a specific section (for small targeted fixes)
        - delete: Remove a skill

        SKILL.md format example:
        ---
        name: skill-name
        description: One-line description of what this skill does
        version: "1.0"
        ---
        # Skill Title

        ## When to Use
        Describe the scenario...

        ## Steps
        1. First step with actual commands...
        2. Second step...

        ## Gotchas
        - Known pitfalls...

        Security: Content is scanned for dangerous patterns before saving. Malicious content will be rejected.
        """)
    public String skill_manage(
            @JsonProperty(required = true)
            @JsonPropertyDescription("Action: create | edit | patch | delete")
            String action,

            @JsonProperty(required = true)
            @JsonPropertyDescription("Skill name (lowercase letters, digits, hyphens, e.g., 'spring-boot-scaffold')")
            String name,

            @JsonProperty
            @JsonPropertyDescription("SKILL.md full content (required for create/edit). YAML frontmatter + markdown body.")
            String content,

            @JsonProperty
            @JsonPropertyDescription("For patch action: the existing text to find and replace")
            String oldText,

            @JsonProperty
            @JsonPropertyDescription("For patch action: the new text to replace with")
            String newText,

            // RFC-063r §2.5: carries the calling agent's ChatOrigin; hidden
            // from the LLM by JsonSchemaGenerator. Used to stamp the new
            // skill with the agent's owning workspace.
            @Nullable ToolContext toolContext
    ) {
        if (action == null || action.isBlank()) {
            return "Error: action is required (create | edit | patch | delete)";
        }
        if (name == null || name.isBlank()) {
            return "Error: name is required";
        }

        String normalizedName = name.strip().toLowerCase();
        if (!NAME_PATTERN.matcher(normalizedName).matches()) {
            return "Error: invalid skill name '" + normalizedName
                    + "'. Must match: lowercase letters, digits, hyphens, dots (1-64 chars, start with letter/digit)";
        }

        Long workspaceId = ChatOrigin.from(toolContext).workspaceId();

        return switch (action.strip().toLowerCase()) {
            case "create" -> doCreate(normalizedName, content, workspaceId);
            case "edit"   -> doEdit(normalizedName, content);
            case "patch"  -> doPatch(normalizedName, oldText, newText);
            case "delete" -> doDelete(normalizedName);
            default -> "Error: unknown action '" + action + "'. Use: create | edit | patch | delete";
        };
    }

    // ==================== Create ====================

    private String doCreate(String name, String content, Long workspaceId) {
        if (content == null || content.isBlank()) {
            return "Error: content is required for create action. Provide full SKILL.md content.";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return "Error: content too large (" + content.length() + " chars, max " + MAX_CONTENT_CHARS + ")";
        }

        // 检查重名
        SkillEntity existing = skillService.findByName(name);
        if (existing != null) {
            return "Error: skill '" + name + "' already exists. Use action='edit' to update or action='patch' for small fixes.";
        }

        // 安全扫描
        String scanError = runSecurityScan(content, name);
        if (scanError != null) return scanError;

        // 创建 skill
        try {
            SkillEntity skill = new SkillEntity();
            skill.setName(name);
            skill.setDescription(extractDescription(content));
            skill.setSkillType("custom");
            skill.setSkillContent(content);
            skill.setEnabled(true);
            skill.setBuiltin(false);
            skill.setVersion(extractVersion(content));
            skill.setSecurityScanStatus("PASSED");
            skill.setWorkspaceId(workspaceId);

            skillService.createSkill(skill);

            // 同步到 workspace 文件系统
            try {
                workspaceManager.exportToWorkspace(name, content);
            } catch (Exception e) {
                log.warn("[SkillManage] Workspace export failed for '{}': {}", name, e.getMessage());
            }

            log.info("[SkillManage] Agent created skill: name={}, contentLen={}", name, content.length());
            return "Skill '" + name + "' created successfully (security scan: PASSED). "
                    + "It is now available in your skill list for future conversations.";
        } catch (Exception e) {
            log.error("[SkillManage] Failed to create skill '{}': {}", name, e.getMessage(), e);
            return "Error creating skill: " + e.getMessage();
        }
    }

    // ==================== Edit (full rewrite) ====================

    private String doEdit(String name, String content) {
        if (content == null || content.isBlank()) {
            return "Error: content is required for edit action. Provide full replacement SKILL.md content.";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return "Error: content too large (" + content.length() + " chars, max " + MAX_CONTENT_CHARS + ")";
        }

        SkillEntity existing = skillService.findByName(name);
        if (existing == null) {
            return "Error: skill '" + name + "' not found. Use action='create' to create it.";
        }
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            return "Error: cannot edit builtin skill '" + name + "'.";
        }

        // 安全扫描
        String scanError = runSecurityScan(content, name);
        if (scanError != null) return scanError;

        try {
            existing.setSkillContent(content);
            existing.setDescription(extractDescription(content));
            existing.setVersion(extractVersion(content));
            existing.setSecurityScanStatus("PASSED");
            skillService.updateSkill(existing);

            try {
                workspaceManager.exportToWorkspace(name, content);
            } catch (Exception e) {
                log.warn("[SkillManage] Workspace export failed for '{}': {}", name, e.getMessage());
            }

            rescanQuietly(existing);

            log.info("[SkillManage] Agent edited skill: name={}, contentLen={}", name, content.length());
            return "Skill '" + name + "' updated successfully (security scan: PASSED).";
        } catch (Exception e) {
            log.error("[SkillManage] Failed to edit skill '{}': {}", name, e.getMessage(), e);
            return "Error editing skill: " + e.getMessage();
        }
    }

    // ==================== Patch (find-and-replace) ====================

    private String doPatch(String name, String oldText, String newText) {
        if (oldText == null || oldText.isBlank()) {
            return "Error: oldText is required for patch action.";
        }
        if (newText == null) {
            return "Error: newText is required for patch action (use empty string to delete a section).";
        }

        SkillEntity existing = skillService.findByName(name);
        if (existing == null) {
            return "Error: skill '" + name + "' not found.";
        }
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            return "Error: cannot patch builtin skill '" + name + "'.";
        }

        String currentContent = existing.getSkillContent();
        if (currentContent == null || currentContent.isBlank()) {
            return "Error: skill '" + name + "' has no content to patch.";
        }

        // 精确匹配
        String patchedContent;
        if (currentContent.contains(oldText)) {
            patchedContent = currentContent.replace(oldText, newText);
        } else {
            // 宽松匹配：归一化空白后重试
            String normalizedCurrent = normalizeWhitespace(currentContent);
            String normalizedOld = normalizeWhitespace(oldText);
            if (normalizedCurrent.contains(normalizedOld)) {
                // 找到原始位置（用归一化版本定位，然后在原文中做替换）
                int normIdx = normalizedCurrent.indexOf(normalizedOld);
                // 回映射到原始文本（近似：找最近的原始位置）
                int approxStart = findApproximatePosition(currentContent, oldText);
                if (approxStart >= 0) {
                    int approxEnd = approxStart + oldText.length();
                    patchedContent = currentContent.substring(0, approxStart) + newText
                            + currentContent.substring(Math.min(approxEnd, currentContent.length()));
                } else {
                    return "Error: could not locate oldText in skill content (fuzzy match found but position mapping failed). "
                            + "Try using action='edit' with full content instead.";
                }
            } else {
                return "Error: oldText not found in skill '" + name + "'. Check for whitespace differences. "
                        + "Tip: use action='edit' to replace entire content if patch is too tricky.";
            }
        }

        if (patchedContent.length() > MAX_CONTENT_CHARS) {
            return "Error: patched content too large (" + patchedContent.length() + " chars, max " + MAX_CONTENT_CHARS + ")";
        }

        // 安全扫描
        String scanError = runSecurityScan(patchedContent, name);
        if (scanError != null) return scanError;

        try {
            existing.setSkillContent(patchedContent);
            existing.setDescription(extractDescription(patchedContent));
            existing.setVersion(extractVersion(patchedContent));
            existing.setSecurityScanStatus("PASSED");
            skillService.updateSkill(existing);

            try {
                workspaceManager.exportToWorkspace(name, patchedContent);
            } catch (Exception e) {
                log.warn("[SkillManage] Workspace export failed for '{}': {}", name, e.getMessage());
            }

            rescanQuietly(existing);

            log.info("[SkillManage] Agent patched skill: name={}", name);
            return "Skill '" + name + "' patched successfully (security scan: PASSED).";
        } catch (Exception e) {
            log.error("[SkillManage] Failed to patch skill '{}': {}", name, e.getMessage(), e);
            return "Error patching skill: " + e.getMessage();
        }
    }

    // ==================== Delete ====================

    private String doDelete(String name) {
        SkillEntity existing = skillService.findByName(name);
        if (existing == null) {
            return "Error: skill '" + name + "' not found.";
        }
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            return "Error: cannot delete builtin skill '" + name + "'.";
        }

        try {
            // RFC-090 §14.5 — agent-triggered delete uses uninstall
            // (logical + archive) so a misbehaving agent can't
            // physically purge a row past recovery.
            skillService.uninstallSkill(existing.getId());
            log.info("[SkillManage] Agent uninstalled skill: name={}", name);
            return "Skill '" + name + "' uninstalled (workspace archived).";
        } catch (Exception e) {
            log.error("[SkillManage] Failed to delete skill '{}': {}", name, e.getMessage(), e);
            return "Error deleting skill: " + e.getMessage();
        }
    }

    // ==================== Helpers ====================

    /**
     * 运行安全扫描。通过返回 null，拒绝返回错误信息字符串。
     */
    private String runSecurityScan(String content, String name) {
        try {
            SkillValidationResult result = securityService.scanContent(content, name);
            if (result.isBlocked()) {
                log.warn("[SkillManage] Security scan BLOCKED skill '{}': {}", name, result.getSummary());
                StringBuilder sb = new StringBuilder();
                sb.append("Security scan BLOCKED: skill content contains dangerous patterns.\n");
                for (SkillValidationResult.Finding f : result.getFindings()) {
                    if (f.getSeverity().isBlockLevel()) {
                        sb.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle());
                        if (f.getRemediation() != null) {
                            sb.append(" → Fix: ").append(f.getRemediation());
                        }
                        sb.append("\n");
                    }
                }
                sb.append("Please fix the issues and try again.");
                return sb.toString();
            }
            // 有 warning 但不 block 的情况，记录日志但允许通过
            if (!result.getWarnings().isEmpty()) {
                log.info("[SkillManage] Security scan passed with {} warnings for skill '{}'",
                        result.getWarnings().size(), name);
            }
            return null; // 通过
        } catch (Exception e) {
            log.error("[SkillManage] Security scan failed for '{}': {}", name, e.getMessage(), e);
            return "Error: security scan failed (" + e.getMessage() + "). Skill not saved.";
        }
    }

    /**
     * Synchronously re-run the resolver pipeline for the modified skill so
     * the active-skills cache and any manifest-projected columns are
     * coherent before this tool call returns. Without this, callers race
     * the debounced 500ms workspace-event refresh and may observe stale
     * state (e.g. the skill detail page showing the previous version).
     */
    private void rescanQuietly(SkillEntity skill) {
        if (skill == null || runtimeService == null) return;
        try {
            runtimeService.rescanSingle(skill);
        } catch (Exception e) {
            log.warn("[SkillManage] Post-write rescan failed for '{}': {}", skill.getName(), e.getMessage());
        }
    }

    /** 从 YAML frontmatter 提取 description */
    private String extractDescription(String content) {
        String fm = extractFrontmatterValue(content, "description");
        return fm != null ? fm : "";
    }

    /** 从 YAML frontmatter 提取 version */
    private String extractVersion(String content) {
        String v = extractFrontmatterValue(content, "version");
        return v != null ? v : "1.0";
    }

    /**
     * 简单提取 YAML frontmatter 中的值（不引入 YAML 库依赖）。
     * 支持格式：{@code key: value} 和 {@code key: "value"}
     */
    private String extractFrontmatterValue(String content, String key) {
        if (content == null || !content.startsWith("---")) return null;
        int endIdx = content.indexOf("---", 3);
        if (endIdx < 0) return null;
        String frontmatter = content.substring(3, endIdx);
        for (String line : frontmatter.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(key + ":")) {
                String value = trimmed.substring(key.length() + 1).strip();
                // 去引号
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    /** 空白归一化（连续空白 → 单空格，trim） */
    private String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /** 近似定位 oldText 在 content 中的位置（容忍空白差异） */
    private int findApproximatePosition(String content, String oldText) {
        // 按行首几个非空白词匹配
        String[] lines = oldText.split("\n");
        if (lines.length == 0) return -1;
        String firstLine = lines[0].strip();
        if (firstLine.isBlank() && lines.length > 1) firstLine = lines[1].strip();
        if (firstLine.isBlank()) return -1;
        // 取前 30 字符作为锚点
        String anchor = firstLine.substring(0, Math.min(30, firstLine.length()));
        return content.indexOf(anchor);
    }
}
