package vip.mate.skill.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vip.mate.common.result.R;
import vip.mate.skill.installer.SkillInstaller;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.skill.installer.ZipSkillFetcher;
import vip.mate.skill.installer.model.*;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.util.List;
import java.util.Map;

/**
 * Skill 安装管理接口
 * <p>
 * 提供从外部源（GitHub / ClawHub 市场）安装、更新、卸载 skill 的能力。
 * 支持异步安装（task_id 轮询模式）和 ClawHub 搜索。
 *
 * @author MateClaw Team
 */
@Tag(name = "技能安装")
@RestController
@RequestMapping("/api/v1/skills/install")
@RequiredArgsConstructor
public class SkillInstallController {

    private final SkillInstaller skillInstaller;
    private final SkillFrontmatterParser frontmatterParser;

    @Operation(summary = "搜索 ClawHub 市场")
    @GetMapping("/hub/search")
    @RequireWorkspaceRole("admin")
    public R<List<HubSkillInfo>> searchHub(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(skillInstaller.searchHub(q, limit));
    }

    @Operation(summary = "开始异步安装 skill")
    @PostMapping("/start")
    @RequireWorkspaceRole("admin")
    public R<InstallTask> startInstall(@RequestBody InstallRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        if (request.getBundleUrl() == null || request.getBundleUrl().isBlank()) {
            return R.fail("bundleUrl is required");
        }
        // Stamp the owning workspace from the request context — never trust
        // a workspaceId smuggled in the JSON body.
        request.setWorkspaceId(workspaceId);
        return R.ok(skillInstaller.startInstall(request));
    }

    @Operation(summary = "查询安装任务状态")
    @GetMapping("/status/{taskId}")
    @RequireWorkspaceRole("admin")
    public R<InstallTask> getStatus(@PathVariable String taskId) {
        InstallTask task = skillInstaller.getTaskStatus(taskId);
        if (task == null) {
            return R.fail("Task not found: " + taskId);
        }
        return R.ok(task);
    }

    @Operation(summary = "取消安装任务")
    @PostMapping("/cancel/{taskId}")
    @RequireWorkspaceRole("admin")
    public R<Void> cancel(@PathVariable String taskId) {
        skillInstaller.cancelTask(taskId);
        return R.ok();
    }

    @Operation(summary = "上传 ZIP 安装 skill")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> uploadZip(
            @RequestPart("file") MultipartFile zipFile,
            @RequestParam(defaultValue = "true") Boolean enable,
            @RequestParam(defaultValue = "false") Boolean overwrite,
            @RequestParam(required = false) String targetName,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        // 校验文件类型
        String filename = zipFile.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".zip")) {
            return R.fail(400, "Only .zip files are accepted");
        }
        try {
            SkillBundle bundle = ZipSkillFetcher.parse(zipFile, frontmatterParser);
            Map<String, Object> result = skillInstaller.installFromBundle(
                    bundle, enable, overwrite, targetName, workspaceId);
            return R.ok(result);
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail("ZIP install failed: " + e.getMessage());
        }
    }

    @Operation(summary = "卸载 skill")
    @DeleteMapping("/{skillName}")
    @RequireWorkspaceRole("admin")
    public R<Map<String, String>> uninstall(@PathVariable String skillName,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        skillInstaller.uninstall(skillName, workspaceId);
        return R.ok(Map.of("message", "Skill '" + skillName + "' uninstalled"));
    }
}
