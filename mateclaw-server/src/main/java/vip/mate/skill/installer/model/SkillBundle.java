package vip.mate.skill.installer.model;

import java.util.Map;

/**
 * 解析后的 skill bundle（从外部源获取的完整 skill 包）
 *
 * @author MateClaw Team
 */
public record SkillBundle(
        /** skill 名称（从 SKILL.md frontmatter 解析） */
        String name,
        /** SKILL.md 完整内容 */
        String content,
        /** references/ 文件映射（相对路径 → 内容） */
        Map<String, String> references,
        /** scripts/ 文件映射（相对路径 → 内容） */
        Map<String, String> scripts,
        /** 来源类型：github / clawhub / local */
        String sourceType,
        /** 来源 URL */
        String sourceUrl,
        /** 版本 */
        String version,
        /** 描述（从 frontmatter 解析） */
        String description,
        /** 作者 */
        String author,
        /** 图标 */
        String icon,
        /** Byte-preserving workspace-relative attachments (scripts/references/templates). */
        Map<String, byte[]> files
) {
    public SkillBundle(String name, String content, Map<String, String> references,
                       Map<String, String> scripts, String sourceType, String sourceUrl,
                       String version, String description, String author, String icon) {
        this(name, content, references, scripts, sourceType, sourceUrl, version, description, author, icon, Map.of());
    }
    /** Merge legacy text-only sources and byte-preserving imports. */
    public Map<String, byte[]> allFiles() {
        Map<String, byte[]> combined = new java.util.LinkedHashMap<>();
        if (references != null) references.forEach((path, text) -> combined.put(
                path.startsWith("references/") ? path : "references/" + path,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (scripts != null) scripts.forEach((path, text) -> combined.put(
                path.startsWith("scripts/") ? path : "scripts/" + path,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (files != null) combined.putAll(files);
        return combined;
    }
}
