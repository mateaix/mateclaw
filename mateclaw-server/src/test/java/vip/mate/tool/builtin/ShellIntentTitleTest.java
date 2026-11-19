package vip.mate.tool.builtin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.tool.local.LocalShellTool;
import vip.mate.tool.local.LocalToolBridgeService;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ShellIntentTitleTest {
    @Test void optionalDisplayTitleIsPublishedWithoutChangingExecutionArguments() throws Exception {
        var shell = spy(new ShellExecuteTool(mock(vip.mate.i18n.I18nService.class),null));
        var context = new ToolContext(Map.of("test",true));
        doReturn("unchanged-result").when(shell).execute_shell_command("git status",5,context);
        var callback = ToolCallbacks.from(shell)[0];
        var schema = new ObjectMapper().readTree(callback.getToolDefinition().inputSchema());
        assertTrue(schema.path("properties").has("title"));
        assertFalse(schema.path("required").toString().contains("title"));
        assertEquals("execute_shell_command",callback.getToolDefinition().name());
        assertEquals("unchanged-result",new ObjectMapper().readTree(callback.call("{\"command\":\"git status\",\"timeoutSeconds\":5,\"title\":\"Inspect current status\"}",context)).asText());
        verify(shell).execute_shell_command("git status",5,context);
    }
    @Test void localShellAlsoPublishesOptionalTitleUnderItsExistingToolName() throws Exception {
        var local = spy(new LocalShellTool(mock(LocalToolBridgeService.class),new ObjectMapper()));
        var context = new ToolContext(Map.of("test",true));
        doReturn("local-result").when(local).local_execute_shell("pwd",null,context);
        var callback = ToolCallbacks.from(local)[0];
        var schema = new ObjectMapper().readTree(callback.getToolDefinition().inputSchema());
        assertEquals("local_execute_shell",callback.getToolDefinition().name());
        assertEquals("local-result", new ObjectMapper().readTree(callback.call("{\"command\":\"pwd\",\"title\":\"Inspect working directory\"}",context)).asText());
        verify(local).local_execute_shell("pwd",null,context);
        assertTrue(schema.path("properties").has("title"));
        assertFalse(schema.path("required").toString().contains("title"));
    }
}
