package vip.mate.tool.builtin;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.tool.ToolInputValidationException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DocxRenderToolReturnDirectTest {

    @Test
    void docxRenderToolsAllowModelToSummarizeGeneratedDeliverable() throws Exception {
        assertModelContinuation("renderDocx", String.class, String.class, String.class, ToolContext.class);
        assertModelContinuation("renderDocxFromFile", String.class, String.class, String.class, ToolContext.class);
        assertModelContinuation("renderDocxFromFiles", java.util.List.class, String.class, String.class, ToolContext.class);
    }

    @Test
    void blankMarkdownIsARecoverableInputFailureNotADirectSuccess() {
        DocxRenderTool tool = new DocxRenderTool(null, null);

        ToolInputValidationException error = assertThrows(ToolInputValidationException.class,
                () -> tool.renderDocx("  ", "report", "A4", null));

        assertTrue(error.getMessage().contains("markdown must not be blank"));
    }

    private static void assertModelContinuation(String methodName, Class<?>... parameterTypes) throws Exception {
        Tool tool = DocxRenderTool.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(Tool.class);

        assertFalse(tool.returnDirect(), methodName + " must let the model summarize the deliverable and remaining work");
    }
}
