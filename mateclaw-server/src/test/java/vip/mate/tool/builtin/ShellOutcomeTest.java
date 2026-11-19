package vip.mate.tool.builtin;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.i18n.I18nService;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.local.LocalShellTool;
import vip.mate.tool.local.LocalToolBridgeService;
import vip.mate.tool.mcp.runtime.McpToolResultCapture;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShellOutcomeTest {
    @TempDir Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalToolBridgeService bridge = mock(LocalToolBridgeService.class);

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void nonzeroExitIsFailureButStderrAloneIsNot() {
        var tool = new ShellExecuteTool(mock(I18nService.class), mock(GeneratedFileCache.class));
        var failed = new McpToolResultCapture();
        var context = ChatOrigin.EMPTY.withWorkspace(1L, workspace.toString()).toToolContext();
        var result = JSONUtil.parseObj(tool.executeShellCommand("printf failed; exit 7", "intent", 5, failed.attach(context)));
        assertEquals(7, result.getInt("exitCode"));
        assertTrue(failed.isError());
        assertTrue(failed.errorText().contains("7"));
        var succeeded = new McpToolResultCapture();
        result = JSONUtil.parseObj(tool.executeShellCommand("printf warning >&2", null, 5, succeeded.attach(context)));
        assertEquals(0, result.getInt("exitCode"));
        assertEquals("warning", result.getStr("stderr"));
        assertFalse(succeeded.isError());
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void timeoutIsFailureAndOriginalResultIsPreserved() {
        var tool = new ShellExecuteTool(mock(I18nService.class), mock(GeneratedFileCache.class));
        var capture = new McpToolResultCapture();
        var context = capture.attach(ChatOrigin.EMPTY.withWorkspace(1L, workspace.toString()).toToolContext());
        var result = JSONUtil.parseObj(tool.executeShellCommand("sleep 3", "intent", 1, context));
        assertTrue(result.getBool("timedOut"));
        assertTrue(capture.isError());
    }

    @Test void desktopOfflineIsFailure() {
        when(bridge.invoke(any(), anyString(), anyString(), anyString(), any(), anyInt(), eq(true)))
                .thenReturn(LocalToolBridgeService.BridgeResult.failure("OFFLINE", "No desktop connected"));
        var capture = new McpToolResultCapture();
        var context = capture.attach(ChatOrigin.EMPTY.toToolContext());
        String result = new LocalShellTool(bridge, mapper).localExecuteShell("printf ok", "intent", 5, context);
        assertEquals("OFFLINE", JSONUtil.parseObj(result).getStr("code"));
        assertTrue(capture.isError());
    }

    @Test void desktopCommandOutcomeIsSeparateFromBridgeSuccess() throws Exception {
        for (String payload : new String[]{"{\"exitCode\":7}", "{\"exitCode\":0,\"timedOut\":true}", "{\"cancelled\":true}", "{\"error\":\"spawn failed\"}"}) {
            when(bridge.invoke(any(), anyString(), anyString(), anyString(), any(), anyInt(), eq(true)))
                    .thenReturn(LocalToolBridgeService.BridgeResult.success(mapper.readTree(payload)));
            var capture = new McpToolResultCapture();
            new LocalShellTool(bridge, mapper).localExecuteShell("printf ok", "intent", 5, capture.attach(ChatOrigin.EMPTY.toToolContext()));
            assertTrue(capture.isError(), payload);
        }
    }

    @Test void successfulDesktopStderrIsNotFailure() throws Exception {
        when(bridge.invoke(any(), anyString(), anyString(), anyString(), any(), anyInt(), eq(true)))
                .thenReturn(LocalToolBridgeService.BridgeResult.success(mapper.readTree("{\"exitCode\":0,\"stderr\":\"warning\"}")));
        var capture = new McpToolResultCapture();
        new LocalShellTool(bridge, mapper).localExecuteShell("printf ok", null, 5, capture.attach(ChatOrigin.EMPTY.toToolContext()));
        assertFalse(capture.isError());
    }
}
