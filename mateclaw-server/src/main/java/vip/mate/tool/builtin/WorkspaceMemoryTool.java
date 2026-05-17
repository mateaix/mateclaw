package vip.mate.tool.builtin;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.workspace.document.MemorySearchHit;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于数据库工作区文件的长期记忆工具。
 * <p>
 * 用于读写 Agent 专属的 AGENTS.md / PROFILE.md / MEMORY.md / memory/*.md。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceMemoryTool {

    private final WorkspaceFileService workspaceFileService;
    private final MemoryRecallTracker memoryRecallTracker;

    @Tool(description = """
            列出指定 Agent 的数据库工作区记忆文件。
            适用于查看 MEMORY.md、PROFILE.md、AGENTS.md 以及 memory/*.md 每日日记是否存在。
            返回结构化 JSON，包括文件名、是否启用为系统提示词、更新时间和大小。
            """)
    public String list_workspace_memory_files(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "可选：按文件名前缀过滤，例如 memory/ 或 MEM", required = false) String filenamePrefix) {

        if (agentId == null) {
            return error("agentId 不能为空");
        }

        List<WorkspaceFileEntity> files = workspaceFileService.listFiles(agentId).stream()
                .filter(file -> filenamePrefix == null || filenamePrefix.isBlank()
                        || (file.getFilename() != null && file.getFilename().startsWith(filenamePrefix)))
                .sorted(Comparator
                        .comparing((WorkspaceFileEntity file) -> file.getSortOrder() != null ? file.getSortOrder() : 0)
                        .thenComparing(WorkspaceFileEntity::getFilename, Comparator.nullsLast(String::compareTo)))
                .toList();

        JSONArray items = new JSONArray();
        for (WorkspaceFileEntity file : files) {
            JSONObject obj = new JSONObject();
            obj.set("filename", file.getFilename());
            obj.set("enabled", Boolean.TRUE.equals(file.getEnabled()));
            obj.set("fileSize", file.getFileSize());
            obj.set("updateTime", file.getUpdateTime() != null ? file.getUpdateTime().toString() : null);
            items.add(obj);
        }

        JSONObject result = new JSONObject();
        result.set("agentId", agentId);
        result.set("count", files.size());
        result.set("files", items);
        return JSONUtil.toJsonPrettyStr(result);
    }

    @Tool(description = """
            读取指定 Agent 的数据库工作区记忆文件内容。
            适用于读取 MEMORY.md、PROFILE.md、AGENTS.md 或 memory/YYYY-MM-DD.md。
            返回结构化 JSON，包括文件名、是否启用、内容和字节数。
            """)
    public String read_workspace_memory_file(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "工作区文件名，例如 MEMORY.md、PROFILE.md、memory/2026-03-31.md") String filename) {

        String validation = validate(agentId, filename);
        if (validation != null) {
            return error(validation);
        }

        WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
        if (file == null) {
            return error("工作区文件不存在: " + filename);
        }

        // 追踪主动检索信号（比被动注入更强的"真实需要"指标）
        String content = file.getContent() != null ? file.getContent() : "";
        memoryRecallTracker.trackActiveRetrieval(agentId, filename, content);

        JSONObject result = new JSONObject();
        result.set("agentId", agentId);
        result.set("filename", file.getFilename());
        result.set("enabled", Boolean.TRUE.equals(file.getEnabled()));
        result.set("fileSize", file.getFileSize());
        result.set("content", content);
        result.set("updateTime", file.getUpdateTime() != null ? file.getUpdateTime().toString() : null);
        return JSONUtil.toJsonPrettyStr(result);
    }

    @vip.mate.tool.ConcurrencyUnsafe("workspace memory write — concurrent writes to the same file would clobber each other")
    @Tool(description = """
            创建或覆写指定 Agent 的数据库工作区记忆文件。
            适用于把提炼后的长期记忆写入 MEMORY.md，或把原始事件写入 memory/YYYY-MM-DD.md。
            如果文件不存在会自动创建；如果已存在则完全覆写。
            为避免覆盖有价值内容，通常应先调用 read_workspace_memory_file 再决定写入。
            注意：新建文件的 enabled 字段默认为 false，表示该文件不会自动纳入系统提示词——这是正常行为，不代表写入失败。
            PROFILE.md / MEMORY.md 等核心记忆文件在首次由种子数据创建时即为 enabled=true；daily note 文件按需读写即可。
            """)
    public String write_workspace_memory_file(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "工作区文件名，例如 MEMORY.md、PROFILE.md、memory/2026-03-31.md") String filename,
            @ToolParam(description = "要写入的完整 Markdown 内容") String content) {

        String validation = validate(agentId, filename);
        if (validation != null) {
            return error(validation);
        }

        WorkspaceFileEntity before = workspaceFileService.getFile(agentId, filename);
        WorkspaceFileEntity saved = workspaceFileService.saveFile(agentId, filename, content != null ? content : "");

        JSONObject result = new JSONObject();
        result.set("agentId", agentId);
        result.set("filename", saved.getFilename());
        result.set("created", before == null);
        result.set("overwritten", before != null);
        result.set("enabled", Boolean.TRUE.equals(saved.getEnabled()));
        result.set("bytesWritten", (content != null ? content : "").getBytes(StandardCharsets.UTF_8).length);
        result.set("message", before == null ? "工作区记忆文件已创建" : "工作区记忆文件已覆写");
        log.info("[WorkspaceMemoryTool] Saved workspace memory file: agentId={}, filename={}", agentId, filename);
        return JSONUtil.toJsonPrettyStr(result);
    }

    @vip.mate.tool.ConcurrencyUnsafe("workspace memory edit — find/replace must serialize per file")
    @Tool(description = """
            通过精确查找替换编辑指定 Agent 的数据库工作区记忆文件。
            适用于在 MEMORY.md 的某个 section 中做增量更新，避免整篇重写。
            默认只替换第一处匹配，replaceAll=true 时替换全部。
            """)
    public String edit_workspace_memory_file(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "工作区文件名，例如 MEMORY.md、PROFILE.md、memory/2026-03-31.md") String filename,
            @ToolParam(description = "要查找的原始文本，要求精确匹配") String oldText,
            @ToolParam(description = "替换后的新文本") String newText,
            @ToolParam(description = "是否替换全部匹配项，默认 false", required = false) Boolean replaceAll) {

        String validation = validate(agentId, filename);
        if (validation != null) {
            return error(validation);
        }
        if (oldText == null || oldText.isEmpty()) {
            return error("oldText 不能为空");
        }
        if (newText == null) {
            newText = "";
        }
        if (oldText.equals(newText)) {
            return error("oldText 和 newText 相同，无需替换");
        }

        WorkspaceFileEntity existing = workspaceFileService.getFile(agentId, filename);
        if (existing == null) {
            return error("工作区文件不存在: " + filename);
        }

        String content = existing.getContent() != null ? existing.getContent() : "";
        if (!content.contains(oldText)) {
            return error("文件中未找到 oldText，请确认文本完全一致");
        }

        boolean replaceAllFlag = Boolean.TRUE.equals(replaceAll);
        String updated;
        int replacements;
        if (replaceAllFlag) {
            replacements = countOccurrences(content, oldText);
            updated = content.replace(oldText, newText);
        } else {
            int idx = content.indexOf(oldText);
            updated = content.substring(0, idx) + newText + content.substring(idx + oldText.length());
            replacements = 1;
        }

        workspaceFileService.saveFile(agentId, filename, updated);

        JSONObject result = new JSONObject();
        result.set("agentId", agentId);
        result.set("filename", filename);
        result.set("replacements", replacements);
        result.set("replaceAll", replaceAllFlag);
        result.set("fileSizeAfter", updated.getBytes(StandardCharsets.UTF_8).length);
        result.set("message", "工作区记忆文件编辑成功");
        log.info("[WorkspaceMemoryTool] Edited workspace memory file: agentId={}, filename={}, replacements={}",
                agentId, filename, replacements);
        return JSONUtil.toJsonPrettyStr(result);
    }

    @Tool(description = """
            Search agent's workspace memory files (MEMORY.md, PROFILE.md, AGENTS.md, memory/*.md) \
            by keyword. Use this BEFORE read_workspace_memory_file when looking for a fact across \
            many memory entries. Returns ranked hits with filename, line number, and snippet \
            (matched terms wrapped in [[...]]).""")
    public String search_workspace_memory(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "关键词或短语，2-64 字符") String query,
            @ToolParam(description = "搜索范围：all（全部）/ memory（MEMORY.md 与 memory/）/ profile / persona，默认 all",
                    required = false) String scope,
            @ToolParam(description = "返回的最大命中数，默认 10，上限 30", required = false) Integer limit) {

        if (agentId == null) {
            return error("agentId 不能为空");
        }
        if (query == null || query.isBlank()) {
            return error("query 不能为空");
        }
        String trimmed = query.trim();
        if (trimmed.length() < 2) {
            return error("query 至少 2 个字符");
        }
        if (trimmed.length() > 64) {
            return error("query 不能超过 64 个字符");
        }

        int effectiveLimit = limit == null ? 10 : Math.min(Math.max(limit, 1), 30);
        Set<String> prefixes = resolveScope(scope);

        List<MemorySearchHit> hits = workspaceFileService.searchSnippets(
                agentId, trimmed, prefixes, effectiveLimit);

        // Treat each unique file in the results as an active retrieval signal —
        // boosts that file's weight in the dream-consolidation ranker the same
        // way an explicit read_workspace_memory_file call would.
        Set<String> retrieved = new HashSet<>();
        for (MemorySearchHit hit : hits) {
            if (retrieved.add(hit.filename())) {
                WorkspaceFileEntity file = workspaceFileService.getFile(agentId, hit.filename());
                if (file != null && file.getContent() != null) {
                    memoryRecallTracker.trackActiveRetrieval(agentId, hit.filename(), file.getContent());
                }
            }
        }

        JSONArray hitsJson = new JSONArray();
        for (MemorySearchHit hit : hits) {
            JSONObject h = new JSONObject();
            h.set("filename", hit.filename());
            h.set("lineNumber", hit.lineNumber());
            h.set("snippet", hit.snippet());
            h.set("score", hit.score());
            hitsJson.add(h);
        }
        JSONObject result = new JSONObject();
        result.set("agentId", agentId);
        result.set("query", trimmed);
        result.set("scope", scope == null || scope.isBlank() ? "all" : scope);
        result.set("totalHits", hits.size());
        result.set("hits", hitsJson);
        if (!hits.isEmpty()) {
            result.set("hint", "Use read_workspace_memory_file to get full context of any hit.");
        }
        return JSONUtil.toJsonPrettyStr(result);
    }

    /** Map the {@code scope} tool argument to a filename-prefix whitelist.
     *  {@code "all"} (or null/blank) targets every memory-class file rather
     *  than every workspace file the agent has, so a search doesn't surface
     *  unrelated docs the user happens to store in the same workspace. */
    private static Set<String> resolveScope(String scope) {
        if (scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope.trim())) {
            return new LinkedHashSet<>(List.of("memory/", "MEMORY.md", "PROFILE.md", "AGENTS.md"));
        }
        return switch (scope.trim().toLowerCase()) {
            case "memory" -> new LinkedHashSet<>(List.of("memory/", "MEMORY.md"));
            case "profile" -> new LinkedHashSet<>(List.of("PROFILE.md"));
            case "persona" -> new LinkedHashSet<>(List.of("AGENTS.md"));
            default -> new LinkedHashSet<>(List.of("memory/", "MEMORY.md", "PROFILE.md", "AGENTS.md"));
        };
    }

    private String validate(Long agentId, String filename) {
        if (agentId == null) {
            return "agentId 不能为空";
        }
        if (filename == null || filename.isBlank()) {
            return "filename 不能为空";
        }
        if (filename.startsWith("/") || filename.startsWith("\\") || filename.contains("..")) {
            return "filename 必须是工作区内的相对逻辑路径，不能包含绝对路径或 ..";
        }
        if (!filename.endsWith(".md")) {
            return "仅支持 Markdown 工作区文件";
        }
        return null;
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    private String error(String message) {
        JSONObject result = new JSONObject();
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
