package vip.mate.tool.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.tool.ConcurrencyUnsafe;

/**
 * Tools that operate on files on the <b>user's local desktop machine</b> (not
 * the server). Each call is forwarded over the desktop WebSocket tunnel to the
 * MateClaw desktop app, which enforces a directory whitelist, prompts the user
 * for approval on writes/edits, and executes the operation locally.
 * <p>
 * These tools require a connected desktop tunnel for the requesting user; when
 * none is connected they return a friendly {@code OFFLINE} error so the agent
 * can fall back to server-side tools or tell the user to open the desktop app.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFileTools {

    /** Read calls are cheap; allow the desktop a short window to reply. */
    private static final int READ_TIMEOUT_SECONDS = 30;
    /** Writes/edits may trigger an approval dialog the user must read first. */
    private static final int APPROVAL_TIMEOUT_SECONDS = 180;

    private final LocalToolBridgeService bridge;
    private final ObjectMapper objectMapper;

    @Tool(description = """
            LOCAL: Read a file on the USER'S LOCAL DESKTOP machine (not the server). \
            Supports an optional 1-based line range. Output is truncated to ~30KB. \
            Requires the MateClaw desktop app to be connected and the path to be \
            inside the user's configured local directory whitelist. Use the plain \
            read_file tool for server-side files.""")
    public String local_read_file(
            @ToolParam(description = "Absolute path on the user's local machine") String filePath,
            @ToolParam(description = "Start line number (1-based, inclusive). Omit to start at line 1", required = false) Integer startLine,
            @ToolParam(description = "End line number (1-based, inclusive). Omit to read to EOF or truncation limit", required = false) Integer endLine,
            @Nullable ToolContext ctx) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("filePath", filePath);
        if (startLine != null) params.put("startLine", startLine);
        if (endLine != null) params.put("endLine", endLine);
        return call(ctx, "local_read_file", "read_file",
                LocalToolBridgeService.CAP_READ, params, READ_TIMEOUT_SECONDS, false);
    }

    @ConcurrencyUnsafe("local file write — must serialize with reads/writes on overlapping paths")
    @Tool(description = """
            LOCAL: Write content to a file on the USER'S LOCAL DESKTOP machine (not \
            the server). Overwrites if it exists, creates parent directories as \
            needed. ALWAYS prompts the user for native approval on the desktop \
            before writing. Requires the path to be inside the local directory \
            whitelist.""")
    public String local_write_file(
            @ToolParam(description = "Absolute path on the user's local machine") String filePath,
            @ToolParam(description = "Full content to write to the file") String content,
            @Nullable ToolContext ctx) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("filePath", filePath);
        params.put("content", content);
        return call(ctx, "local_write_file", "write_file",
                LocalToolBridgeService.CAP_WRITE, params, APPROVAL_TIMEOUT_SECONDS, true);
    }

    @ConcurrencyUnsafe("local in-place edit — must not race with reads/writes on the same path")
    @Tool(description = """
            LOCAL: Edit a file on the USER'S LOCAL DESKTOP machine via find-and-replace. \
            Replaces the first exact match of oldText with newText (set replaceAll=true \
            for all). ALWAYS prompts the user for native approval on the desktop before \
            editing. Requires the path to be inside the local directory whitelist.""")
    public String local_edit_file(
            @ToolParam(description = "Absolute path on the user's local machine") String filePath,
            @ToolParam(description = "Original text to find (exact match)") String oldText,
            @ToolParam(description = "Replacement text") String newText,
            @ToolParam(description = "Replace all occurrences, default false (first only)", required = false) Boolean replaceAll,
            @Nullable ToolContext ctx) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("filePath", filePath);
        params.put("oldText", oldText);
        params.put("newText", newText);
        params.put("replaceAll", Boolean.TRUE.equals(replaceAll));
        return call(ctx, "local_edit_file", "edit_file",
                LocalToolBridgeService.CAP_EDIT, params, APPROVAL_TIMEOUT_SECONDS, true);
    }

    @Tool(description = """
            LOCAL: List entries in a directory on the USER'S LOCAL DESKTOP machine \
            (not the server). Returns each entry with a name and a type marker \
            (file/dir). Requires the directory to be inside the local directory \
            whitelist.""")
    public String local_list_dir(
            @ToolParam(description = "Absolute directory path on the user's local machine") String dirPath,
            @Nullable ToolContext ctx) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("dirPath", dirPath);
        return call(ctx, "local_list_dir", "list_dir",
                LocalToolBridgeService.CAP_LIST, params, READ_TIMEOUT_SECONDS, false);
    }

    @Tool(description = """
            LOCAL: Get metadata for a path on the USER'S LOCAL DESKTOP machine: size \
            in bytes, last-modified time, and whether it is a directory. Requires the \
            path to be inside the local directory whitelist.""")
    public String local_stat(
            @ToolParam(description = "Absolute path on the user's local machine") String path,
            @Nullable ToolContext ctx) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", path);
        return call(ctx, "local_stat", "stat",
                LocalToolBridgeService.CAP_STAT, params, READ_TIMEOUT_SECONDS, false);
    }

    private String call(@Nullable ToolContext ctx, String toolName, String method, String capability,
                        ObjectNode params, int timeoutSeconds, boolean mutating) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        LocalToolBridgeService.BridgeResult result =
                bridge.invoke(origin, toolName, method, capability, params, timeoutSeconds, mutating);
        return LocalToolFormat.render(result, objectMapper);
    }
}
