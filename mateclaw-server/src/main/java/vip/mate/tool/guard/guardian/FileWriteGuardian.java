package vip.mate.tool.guard.guardian;

import lombok.extern.slf4j.Slf4j;
import vip.mate.tool.guard.model.*;

import java.util.List;
import java.util.Set;

/**
 * File-write guardian — historically marked every write_file / edit_file call
 * as MEDIUM risk and forced an approval popup. Disabled (no @Component)
 * because in-workspace writes are already path-bounded by
 * {@code FilePathGuardian} + {@code WorkspacePathGuard.validatePath()}, and
 * the per-call approval prompt drove operators to give up on multi-file
 * workflows (a 22-chapter docx generation = 22 popups). The class is kept on
 * disk for two reasons: (1) re-enabling guardian-level write approval is a
 * one-line `@Component` change if a deployment really wants it, (2) it
 * documents the historical behavior for anyone diffing why approval suddenly
 * stopped firing on write_file. The mate_tool_guard_config row's
 * guarded_tools_json was narrowed to {@code execute_shell_command} in V51.
 */
@Slf4j
public class FileWriteGuardian implements ToolGuardGuardian {

    private static final Set<String> FILE_WRITE_TOOL_NAMES = Set.of(
            "write_file", "append_file", "edit_file"
    );

    @Override
    public boolean supports(ToolInvocationContext context) {
        return context.toolName() != null && FILE_WRITE_TOOL_NAMES.contains(context.toolName());
    }

    @Override
    public int priority() {
        return 150;
    }

    @Override
    public List<GuardFinding> evaluate(ToolInvocationContext context) {
        return List.of(new GuardFinding(
                "FILE_WRITE_OPERATION",
                GuardSeverity.MEDIUM,
                GuardCategory.COMMAND_INJECTION,
                "文件写入操作",
                "检测到文件写入/编辑操作，需要用户确认",
                "请确认文件内容和目标路径",
                context.toolName(),
                null,
                "file_write_tool_default",
                null
        ));
    }
}
