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
 * Executes a shell command on the <b>user's local desktop machine</b> (not the
 * server) over the desktop tunnel. The desktop app prompts the user for native
 * approval before running, uses {@code cmd.exe} on Windows and {@code /bin/sh}
 * on macOS/Linux, and truncates stdout/stderr to ~10KB each — mirroring the
 * server-side shell tool's limits.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalShellTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    /** Extra slack on top of the command timeout so the user can read the approval dialog. */
    private static final int APPROVAL_SLACK_SECONDS = 180;

    private final LocalToolBridgeService bridge;
    private final ObjectMapper objectMapper;

    @ConcurrencyUnsafe("local shell command execution can mutate global state on the user's machine")
    @Tool(description = """
            LOCAL: Execute a shell command on the USER'S LOCAL DESKTOP machine (not \
            the server). Uses cmd.exe on Windows, /bin/sh on macOS/Linux. ALWAYS \
            prompts the user for native approval on the desktop before running. \
            Returns structured JSON with exitCode, stdout, stderr, timedOut \
            (stdout/stderr truncated to ~10KB each). Use execute_shell_command for \
            server-side execution.""")
    public String local_execute_shell(
            @ToolParam(description = "Shell command to execute on the user's local machine") String command,
            @ToolParam(description = "Timeout in seconds, default 60, hard cap 300", required = false) Integer timeoutSeconds,
            @Nullable ToolContext ctx) {
        int cmdTimeout = (timeoutSeconds != null && timeoutSeconds > 0) ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        cmdTimeout = Math.min(cmdTimeout, MAX_TIMEOUT_SECONDS);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("command", command);
        params.put("timeoutSeconds", cmdTimeout);

        ChatOrigin origin = ChatOrigin.from(ctx);
        LocalToolBridgeService.BridgeResult result = bridge.invoke(
                origin, "local_execute_shell", "execute_shell",
                LocalToolBridgeService.CAP_SHELL, params,
                cmdTimeout + APPROVAL_SLACK_SECONDS, true);
        return LocalToolFormat.render(result, objectMapper);
    }
}
