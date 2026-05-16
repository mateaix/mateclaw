package vip.mate.skill.installer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import vip.mate.skill.installer.model.*;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.SkillWorkspaceEvent;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 安装核心服务
 * <p>
 * 管理从外部源（GitHub / ClawHub）安装 skill 的完整流程：
 * URL 解析 → bundle 获取 → workspace 落盘 → 数据库注册 → 运行时刷新。
 * <p>
 * 支持异步安装（task_id 轮询模式），参考 MateClaw 实现。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillInstaller {

    private final BundleResolver bundleResolver;
    private final SkillHubClient skillHubClient;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillService skillService;
    private final SkillFileService skillFileService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 安装任务追踪 */
    private final ConcurrentHashMap<String, InstallTask> tasks = new ConcurrentHashMap<>();

    /**
     * 启动异步安装任务
     */
    public InstallTask startInstall(InstallRequest request) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        InstallTask task = InstallTask.create(taskId, request.getBundleUrl());
        tasks.put(taskId, task);

        doInstallAsync(taskId, request);
        return task;
    }

    /**
     * 获取安装任务状态
     */
    public InstallTask getTaskStatus(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 取消安装任务
     */
    public void cancelTask(String taskId) {
        InstallTask task = tasks.get(taskId);
        if (task != null && task.getStatus() == InstallTask.InstallStatus.INSTALLING) {
            task.setCancelRequested(true);
            task.markCancelled();
            log.info("Install task {} cancelled", taskId);
        }
    }

    /**
     * RFC-090 §14.5 — user-facing uninstall: logical delete + workspace
     * archive. Re-installing the same name later is supported. For
     * admin-only physical removal, call
     * {@code SkillService.hardDeleteSkill} via {@code DELETE /skills/{id}}.
     */
    public void uninstall(String skillName, Long workspaceId) {
        List<SkillEntity> skills = skillService.listSkills();
        SkillEntity target = skills.stream()
                .filter(s -> s.getName().equals(skillName))
                .findFirst()
                .orElse(null);

        if (target != null) {
            // A workspace may only uninstall the skills it owns. Builtin
            // skills are global and rejected by uninstallSkill itself.
            if (!Boolean.TRUE.equals(target.getBuiltin())) {
                long requested = workspaceId != null
                        ? workspaceId : SkillService.DEFAULT_WORKSPACE_ID;
                long owner = target.getWorkspaceId() != null
                        ? target.getWorkspaceId() : SkillService.DEFAULT_WORKSPACE_ID;
                if (owner != requested) {
                    throw new vip.mate.exception.MateClawException("err.common.wrong_workspace", 403,
                            "Skill '" + skillName + "' does not belong to the current workspace");
                }
            }
            skillService.uninstallSkill(target.getId());
        }
        log.info("Uninstalled skill: {}", skillName);
    }

    /**
     * 搜索 ClawHub 市场（委托给 SkillHubClient）
     */
    public List<HubSkillInfo> searchHub(String query, int limit) {
        try {
            return skillHubClient.search(query, limit);
        } catch (Exception e) {
            log.warn("Hub search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 异步安装流程 ====================

    @Async
    public CompletableFuture<Void> doInstallAsync(String taskId, InstallRequest request) {
        InstallTask task = tasks.get(taskId);
        if (task == null) return CompletableFuture.completedFuture(null);

        task.markInstalling();

        try {
            // 1. 解析 bundle
            SkillBundle bundle = bundleResolver.resolve(request.getBundleUrl(), request.getVersion());
            if (bundle == null) {
                task.markFailed("Failed to resolve skill bundle from: " + request.getBundleUrl());
                return CompletableFuture.completedFuture(null);
            }

            if (task.isCancelRequested()) {
                task.markCancelled();
                return CompletableFuture.completedFuture(null);
            }

            // 2. 确定 skill 名称
            String skillName = request.getTargetName() != null ? request.getTargetName() : bundle.name();
            if (skillName == null || skillName.isBlank()) {
                task.markFailed("Cannot determine skill name from bundle");
                return CompletableFuture.completedFuture(null);
            }

            // 3. 检查是否已存在
            boolean exists = skillService.listSkills().stream()
                    .anyMatch(s -> s.getName().equals(skillName));
            if (exists && !Boolean.TRUE.equals(request.getOverwrite())) {
                task.markFailed("Skill '" + skillName + "' already exists. Set overwrite=true to replace.");
                return CompletableFuture.completedFuture(null);
            }

            if (task.isCancelRequested()) {
                task.markCancelled();
                return CompletableFuture.completedFuture(null);
            }

            // 4. Materialize SKILL.md (overwrite on reinstall, keep on first create).
            workspaceManager.initWorkspace(skillName, bundle.content(), exists);

            if (task.isCancelRequested()) {
                workspaceManager.archiveWorkspace(skillName);
                task.markCancelled();
                return CompletableFuture.completedFuture(null);
            }

            // 5. Register/update the skill row first so we have an id for the file rows.
            SkillEntity skillEntity = upsertSkillRow(bundle, skillName, exists,
                    Boolean.TRUE.equals(request.getEnable()), request.getWorkspaceId());

            if (task.isCancelRequested()) {
                task.markCancelled();
                return CompletableFuture.completedFuture(null);
            }

            // 6. Persist bundle files: DB is canonical, FS is the materialized cache.
            //    Empty-bundle guard protects both sides from a malformed bundle
            //    silently wiping pre-existing scripts/references.
            boolean force = Boolean.TRUE.equals(request.getForcePrune());
            persistBundleFiles(skillEntity, bundle, force, "url");

            // 7. Publish event for runtime refresh / sibling-node materialization.
            eventPublisher.publishEvent(new SkillWorkspaceEvent(
                    skillName, SkillWorkspaceEvent.Type.INSTALLED,
                    workspaceManager.resolveConventionPath(skillName)));

            task.markCompleted(InstallResult.builder()
                    .name(skillName)
                    .enabled(Boolean.TRUE.equals(request.getEnable()))
                    .sourceUrl(bundle.sourceUrl())
                    .sourceType(bundle.sourceType())
                    .build());

            log.info("Skill '{}' installed successfully from {}", skillName, bundle.sourceUrl());

        } catch (Exception e) {
            log.error("Install task {} failed: {}", taskId, e.getMessage(), e);
            task.markFailed(e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 同步安装 SkillBundle（用于 ZIP 上传等本地解析场景，无需异步任务）
     *
     * @return 安装结果 Map（skillId, name, version, filesCount）
     */
    public Map<String, Object> installFromBundle(SkillBundle bundle, boolean enable, boolean overwrite,
                                                  String targetName, Long workspaceId) {
        String skillName = (targetName != null && !targetName.isBlank()) ? targetName : bundle.name();
        if (skillName == null || skillName.isBlank()) {
            throw new vip.mate.exception.MateClawException("err.skill.name_required", "Cannot determine skill name from bundle");
        }

        boolean exists = skillService.listSkills().stream()
                .anyMatch(s -> s.getName().equals(skillName));
        if (exists && !overwrite) {
            throw new vip.mate.exception.MateClawException("err.skill.name_exists",
                    "Skill '" + skillName + "' already exists. Enable overwrite to replace.");
        }

        // Materialize SKILL.md (always overwrite on reinstall path).
        workspaceManager.initWorkspace(skillName, bundle.content(), exists);

        // Register/update skill row first so we have an id to anchor the file rows.
        SkillEntity skillEntity = upsertSkillRow(bundle, skillName, exists, enable, workspaceId);

        // DB-canonical, FS-cache. Empty-bundle guard on both sides.
        persistBundleFiles(skillEntity, bundle, false, "zip");

        eventPublisher.publishEvent(new SkillWorkspaceEvent(
                skillName, SkillWorkspaceEvent.Type.INSTALLED,
                workspaceManager.resolveConventionPath(skillName)));

        int filesCount = (bundle.references() != null ? bundle.references().size() : 0)
                + (bundle.scripts() != null ? bundle.scripts().size() : 0) + 1;

        log.info("Skill '{}' installed from ZIP (v{}, {} files)", skillName, bundle.version(), filesCount);

        return Map.of(
                "skillId", skillEntity.getId(),
                "name", skillName,
                "version", bundle.version() != null ? bundle.version() : "",
                "filesCount", filesCount
        );
    }

    /**
     * Insert or update the {@code mate_skill} row from a bundle. Returns the
     * persisted entity so callers have its id for downstream file writes.
     *
     * <p>{@code workspaceId} is stamped only on the insert path — an
     * existing skill keeps its current owning workspace so a re-install
     * never silently migrates a skill between workspaces.
     */
    private SkillEntity upsertSkillRow(SkillBundle bundle, String skillName, boolean exists,
                                       boolean enable, Long workspaceId) {
        SkillEntity skillEntity;
        if (exists) {
            skillEntity = skillService.listSkills().stream()
                    .filter(s -> s.getName().equals(skillName))
                    .findFirst().orElseThrow();
            skillEntity.setSkillContent(bundle.content());
            skillEntity.setDescription(bundle.description());
            skillEntity.setVersion(bundle.version());
            skillEntity.setAuthor(bundle.author());
            skillEntity.setIcon(bundle.icon());
            skillEntity.setConfigJson(buildConfigJson(bundle));
            if (enable) skillEntity.setEnabled(true);
            skillService.updateSkill(skillEntity);
        } else {
            skillEntity = new SkillEntity();
            skillEntity.setName(skillName);
            skillEntity.setDescription(bundle.description());
            skillEntity.setSkillType("dynamic");
            skillEntity.setVersion(bundle.version());
            skillEntity.setAuthor(bundle.author());
            skillEntity.setIcon(bundle.icon());
            skillEntity.setSkillContent(bundle.content());
            skillEntity.setConfigJson(buildConfigJson(bundle));
            skillEntity.setEnabled(enable);
            skillEntity.setWorkspaceId(workspaceId);
            skillService.createSkill(skillEntity);
        }
        return skillEntity;
    }

    /**
     * Write bundle files to both DB (canonical) and FS (cache) using the
     * same prefixed-key map. Logs a single combined summary so multi-instance
     * deployments can see what each node persisted vs preserved.
     */
    private void persistBundleFiles(SkillEntity skillEntity, SkillBundle bundle, boolean force, String origin) {
        Map<String, String> combined = new LinkedHashMap<>();
        if (bundle.references() != null) {
            for (var e : bundle.references().entrySet()) {
                String key = e.getKey().startsWith("references/") ? e.getKey() : "references/" + e.getKey();
                combined.put(key, e.getValue());
            }
        }
        if (bundle.scripts() != null) {
            for (var e : bundle.scripts().entrySet()) {
                String key = e.getKey().startsWith("scripts/") ? e.getKey() : "scripts/" + e.getKey();
                combined.put(key, e.getValue());
            }
        }

        var dbApply = skillFileService.applyBundleFiles(skillEntity.getId(), combined, force);
        var fsApply = workspaceManager.applyBundleFiles(skillEntity.getName(),
                bundle.references(), bundle.scripts(), force);

        log.info("Persisted bundle for '{}' ({}): db(write={}, prune={}, preservedScripts={}, preservedRefs={}) " +
                        "fs(refs write={}, prune={}, preserved={} | scripts write={}, prune={}, preserved={})",
                skillEntity.getName(), origin,
                dbApply.rowsWritten(), dbApply.rowsPruned(),
                dbApply.scriptsPreservedDueToEmptyBundle(), dbApply.referencesPreservedDueToEmptyBundle(),
                fsApply.referencesWritten(), fsApply.referencesPruned(), fsApply.referencesPreservedDueToEmptyBundle(),
                fsApply.scriptsWritten(), fsApply.scriptsPruned(), fsApply.scriptsPreservedDueToEmptyBundle());
    }

    // ==================== 工具方法 ====================

    private String buildConfigJson(SkillBundle bundle) {
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("upstream", bundle.sourceType());
            config.put("entryFile", "SKILL.md");

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", bundle.sourceType());
            source.put("url", bundle.sourceUrl());
            source.put("installedAt", LocalDateTime.now().toString());
            source.put("installedVersion", bundle.version());
            config.put("source", source);

            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            return "{\"upstream\":\"" + bundle.sourceType() + "\"}";
        }
    }

}
