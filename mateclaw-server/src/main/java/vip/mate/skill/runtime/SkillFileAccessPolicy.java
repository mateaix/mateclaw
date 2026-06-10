package vip.mate.skill.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 技能文件访问策略
 * 确保只能访问 skillDir 内的 references/、scripts/ 和 templates/ 文件
 */
@Slf4j
@Component
public class SkillFileAccessPolicy {

    /**
     * 验证文件路径是否安全
     *
     * @param skillDir 技能根目录
     * @param relativePath 相对路径（必须以 references/、scripts/ 或 templates/ 开头）
     * @return 归一化后的绝对路径，如果不安全则返回 null
     */
    public Path validateAndResolve(Path skillDir, String relativePath) {
        if (skillDir == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }

        // 归一化路径分隔符
        String normalized = relativePath.replace("\\", "/");

        // 必须以 references/、scripts/ 或 templates/ 开头
        if (!normalized.startsWith("references/")
                && !normalized.startsWith("scripts/")
                && !normalized.startsWith("templates/")) {
            log.warn("Invalid path prefix: {}", relativePath);
            return null;
        }

        // 禁止路径遍历
        if (normalized.contains("..") || normalized.startsWith("/")) {
            log.warn("Path traversal detected: {}", relativePath);
            return null;
        }

        // 解析为绝对路径
        Path resolved = skillDir.resolve(normalized).normalize();

        // 确保解析后的路径仍在 skillDir 内
        if (!resolved.startsWith(skillDir)) {
            log.warn("Path escapes skill directory: {}", relativePath);
            return null;
        }

        return resolved;
    }

    /**
     * 验证脚本路径（只能在 scripts/ 下）
     */
    public Path validateScriptPath(Path skillDir, String scriptPath) {
        Path resolved = validateAndResolve(skillDir, scriptPath);
        if (resolved == null) {
            return null;
        }

        // 必须在 scripts/ 目录下
        Path scriptsDir = skillDir.resolve("scripts");
        if (!resolved.startsWith(scriptsDir)) {
            log.warn("Script path must be under scripts/: {}", scriptPath);
            return null;
        }

        return resolved;
    }
}
