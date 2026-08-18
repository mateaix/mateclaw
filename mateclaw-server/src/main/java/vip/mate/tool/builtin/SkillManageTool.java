package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.skill.event.SkillAuthoredEvent;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillOrigin;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.SkillSecurityService;
import vip.mate.skill.runtime.SkillValidationResult;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.util.regex.Pattern;

/**
 * RFC-023: Agent 自治 Skill 管理工具
 * <p>
 * 让 Agent 在对话中自主创建、编辑、修补和删除 Skill。
 * 每次写入前强制安全扫描，失败则拒绝并返回原因。
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
    private final SkillFileService skillFileService;
    private final SkillSecurityService securityService;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillRuntimeService runtimeService;
    private final ApplicationEventPublisher eventPublisher;

    /** Skill 名称格式：小写字母/数字/连字符/下划线/点，首字符必须是字母或数字 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    /** Skill 内容最大长度（~25K tokens） */
    private static final int MAX_CONTENT_CHARS = 100_000;
    private static final Pattern AUTONOMOUS_UNSAFE_INSTRUCTION = Pattern.compile(
            "(?is)(ignore\\s+(?:all\\s+)?(?:previous|prior)\\s+instructions|system\\s+prompt|"
                    + "bypass\\s+(?:the\\s+)?(?:approval|guard|security)|disable\\s+(?:the\\s+)?(?:guard|approval|security)|"
                    + "(?:read|collect|dump|upload|send|exfiltrat\\w*)[^\\n]{0,100}(?:credential|secret|token|password|private key|environment variable)|"
                    + "curl[^\\n]{0,120}(?:--data|-d\\s|--upload|-T\\s)|rm\\s+-r?f\\s+/|/dev/tcp/|nc\\s+-e)");
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+[a-z0-9._~+/-]{12,}|(?:api[_-]?key|password|passwd|secret|token)\\s*[:=]\\s*[^\\s,;]{6,}|sk-[a-z0-9_-]{12,})");

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
        - write_file: Write a supporting file under the skill's references/, scripts/ or
          templates/ directory (e.g. a long reference doc the SKILL.md links to, a re-runnable
          script, or an output template). Put the file body in 'content' and the path in
          'filePath'. Keep SKILL.md itself lean and move bulky detail into references/.
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

            @JsonProperty
            @JsonPropertyDescription("For write_file action: relative path under references/, scripts/ or templates/ (e.g. 'references/api.md', 'scripts/run.sh', 'templates/report.html'). No '..' allowed.")
            String filePath,

            // RFC-063r §2.5: carries the calling agent's ChatOrigin; hidden
            // from the LLM by JsonSchemaGenerator. Used to stamp the new
            // skill with the agent's owning workspace.
            @Nullable ToolContext toolContext
    ) {
        // A tool call is by definition a live conversation turn, so anything
        // arriving here was asked for by a person. Autonomous callers use
        // skillManageAs() and declare their own origin.
        return skillManageAs(SkillOrigin.USER, action, name, content, oldText, newText, filePath, toolContext);
    }

    /**
     * Same pipeline as {@link #skill_manage}, with the authorship stamp made
     * explicit for callers that are not a user-facing turn — the reflection
     * reviewer and the routine promoter.
     *
     * <p>Not exposed to the model: origin is a trust boundary, and a value the
     * model could set would be worth nothing. Routing autonomous writes through
     * the same method keeps them subject to the identical security scan, name
     * validation, builtin guard, and workspace export.
     *
     * @param skillOrigin authorship to stamp on a newly created skill
     */
    public String skillManageAs(SkillOrigin skillOrigin, String action, String name, String content,
                                String oldText, String newText, String filePath,
                                @Nullable ToolContext toolContext) {
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

        ChatOrigin origin = ChatOrigin.from(toolContext);
        Long workspaceId = origin.workspaceId();
        String sourceConversationId = origin.conversationId();

        // Agent-authored mutations must always carry a trusted workspace.
        // Falling back to workspace 1 here would turn a missing origin into a
        // cross-tenant write primitive.
        if (workspaceId == null || workspaceId <= 0) {
            return "Error: workspace context is required for skill mutations";
        }

        return switch (action.strip().toLowerCase()) {
            case "create"     -> doCreate(normalizedName, content, workspaceId, sourceConversationId,
                                          origin.agentId(), skillOrigin);
            case "edit"       -> doEdit(normalizedName, content, workspaceId, skillOrigin);
            case "patch"      -> doPatch(normalizedName, oldText, newText, workspaceId, skillOrigin);
            case "write_file" -> doWriteFile(normalizedName, filePath, content, workspaceId, skillOrigin);
            case "delete"     -> doDelete(normalizedName, workspaceId);
            default -> "Error: unknown action '" + action + "'. Use: create | edit | patch | write_file | delete";
        };
    }

    // ==================== Create ====================

    private String doCreate(String name, String content, Long workspaceId, String sourceConversationId,
                            Long agentId, SkillOrigin skillOrigin) {
        if (content == null || content.isBlank()) {
            return "Error: content is required for create action. Provide full SKILL.md content.";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return "Error: content too large (" + content.length() + " chars, max " + MAX_CONTENT_CHARS + ")";
        }
        String autonomousError = runAutonomousPolicy(content, skillOrigin);
        if (autonomousError != null) return autonomousError;

        // 检查重名
        SkillEntity existing = skillService.findByName(name, workspaceId);
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
            // Stamp the originating conversation so the lifecycle curator can
            // age this skill under its AGENT_CREATED scope. Without it,
            // agent-authored skills are invisible to the curator's default sweep.
            if (sourceConversationId != null && !sourceConversationId.isBlank()) {
                skill.setSourceConversationId(sourceConversationId);
            }
            // Authorship decides whether autonomous curation may later age or
            // rewrite this skill. Stamped here because this is the only point
            // that still knows whether a user was present.
            skill.setOrigin((skillOrigin == null ? SkillOrigin.USER : skillOrigin).code());

            skillService.createSkill(skill);

            // 同步到 workspace 文件系统
            try {
                workspaceManager.exportToWorkspace(name, content, skill.getWorkspaceId());
            } catch (Exception e) {
                log.warn("[SkillManage] Workspace export failed for '{}': {}", name, e.getMessage());
            }

            // Announce authorship so the agent layer can make the skill
            // reachable from the authoring agent's own catalog. Best-effort:
            // the skill is already persisted, so a listener failure must not
            // turn a successful create into an error for the model.
            try {
                eventPublisher.publishEvent(new SkillAuthoredEvent(
                        skill.getId(), name, agentId, sourceConversationId, skill.getWorkspaceId()));
            } catch (Exception e) {
                log.warn("[SkillManage] SkillAuthoredEvent publish failed for '{}': {}", name, e.getMessage());
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

    private String doEdit(String name, String content, Long workspaceId, SkillOrigin skillOrigin) {
        if (content == null || content.isBlank()) {
            return "Error: content is required for edit action. Provide full replacement SKILL.md content.";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return "Error: content too large (" + content.length() + " chars, max " + MAX_CONTENT_CHARS + ")";
        }
        String autonomousError = runAutonomousPolicy(content, skillOrigin);
        if (autonomousError != null) return autonomousError;

        SkillEntity existing = skillService.findByName(name, workspaceId);
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
                workspaceManager.exportToWorkspace(name, content, existing.getWorkspaceId());
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

    private String doPatch(String name, String oldText, String newText, Long workspaceId,
                           SkillOrigin skillOrigin) {
        if (oldText == null || oldText.isBlank()) {
            return "Error: oldText is required for patch action.";
        }
        if (newText == null) {
            return "Error: newText is required for patch action (use empty string to delete a section).";
        }

        SkillEntity existing = skillService.findByName(name, workspaceId);
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

        // Autonomous patches must be exact and unambiguous. The previous
        // whitespace-normalized offset mapping could delete unrelated bytes
        // because normalized and original lengths differ; String#replace also
        // changed every repeated occurrence when the reviewer saw only one.
        int first = currentContent.indexOf(oldText);
        if (first < 0) {
            return "Error: oldText not found exactly in skill '" + name + "'.";
        }
        if (currentContent.indexOf(oldText, first + oldText.length()) >= 0) {
            return "Error: oldText occurs more than once in skill '" + name
                    + "'; provide a larger unique context block.";
        }
        String patchedContent = currentContent.substring(0, first) + newText
                + currentContent.substring(first + oldText.length());

        if (patchedContent.length() > MAX_CONTENT_CHARS) {
            return "Error: patched content too large (" + patchedContent.length() + " chars, max " + MAX_CONTENT_CHARS + ")";
        }
        String autonomousError = runAutonomousPolicy(patchedContent, skillOrigin);
        if (autonomousError != null) return autonomousError;

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
                workspaceManager.exportToWorkspace(name, patchedContent, existing.getWorkspaceId());
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

    // ==================== Write supporting file ====================

    /**
     * Write a supporting file under the skill's {@code references/} or
     * {@code scripts/} directory. The path is validated and confined to the
     * skill workspace by {@link SkillWorkspaceManager#writeWorkspaceFile}; the
     * content is security-scanned just like SKILL.md so an agent can't drop a
     * dangerous script alongside an otherwise-clean skill.
     */
    private String doWriteFile(String name, String filePath, String content, Long workspaceId,
                               SkillOrigin skillOrigin) {
        if (filePath == null || filePath.isBlank()) {
            return "Error: filePath is required for write_file (e.g. 'references/api.md', 'scripts/run.sh' or 'templates/report.html').";
        }
        if (content == null) {
            return "Error: content is required for write_file action.";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return "Error: content too large (" + content.length() + " chars, max " + MAX_CONTENT_CHARS + ")";
        }
        String autonomousError = runAutonomousPolicy(content, skillOrigin);
        if (autonomousError != null) return autonomousError;

        SkillEntity existing = skillService.findByName(name, workspaceId);
        if (existing == null) {
            return "Error: skill '" + name + "' not found. Create it first with action='create'.";
        }
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            return "Error: cannot write files into builtin skill '" + name + "'.";
        }

        // Security scan the file body — scripts especially must be screened.
        String scanError = runSecurityScan(content, name);
        if (scanError != null) {
            return scanError;
        }

        try {
            workspaceManager.writeWorkspaceFile(name, filePath, content, existing.getWorkspaceId());
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage()
                    + " (paths must start with references/, scripts/ or templates/, and may not contain '..').";
        } catch (Exception e) {
            log.error("[SkillManage] Failed to write file '{}' for skill '{}': {}", filePath, name, e.getMessage(), e);
            return "Error writing skill file: " + e.getMessage();
        }

        // Mirror into the canonical mate_skill_file store so the file
        // survives node changes and is visible to DB-reading consumers
        // (admin file editor, multi-instance workspace sync).
        try {
            skillFileService.upsertFile(existing.getId(), filePath.replace('\\', '/'), content);
        } catch (Exception e) {
            log.warn("[SkillManage] Canonical store write failed for '{}' of skill '{}': {}",
                    filePath, name, e.getMessage());
        }

        rescanQuietly(existing);

        log.info("[SkillManage] Agent wrote skill file: skill={}, path={}", name, filePath);
        return "File '" + filePath + "' written to skill '" + name + "' (security scan: PASSED).";
    }

    // ==================== Delete ====================

    private String doDelete(String name, Long workspaceId) {
        SkillEntity existing = skillService.findByName(name, workspaceId);
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

    private String runAutonomousPolicy(String content, SkillOrigin origin) {
        if (origin == null || origin == SkillOrigin.USER || content == null) {
            return null;
        }
        if (SECRET_PATTERN.matcher(content).find()) {
            return "Error: autonomous skill content may not persist credentials or secrets";
        }
        if (AUTONOMOUS_UNSAFE_INSTRUCTION.matcher(content).find()) {
            return "Error: autonomous skill content violates the persistent-instruction policy";
        }
        return null;
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

}
