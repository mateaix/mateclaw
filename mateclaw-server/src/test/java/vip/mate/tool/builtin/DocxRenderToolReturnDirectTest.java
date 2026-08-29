package vip.mate.tool.builtin;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.tool.ToolInputValidationException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxRenderToolReturnDirectTest {

    @Test
    void docxRenderToolsReturnGeneratedFileDirectly() throws Exception {
        assertReturnDirect("renderDocx", String.class, String.class, String.class, ToolContext.class);
        assertReturnDirect("renderDocxFromFile", String.class, String.class, String.class, ToolContext.class);
        assertReturnDirect("renderDocxFromFiles", java.util.List.class, String.class, String.class, ToolContext.class);
    }

    @Test
    void blankMarkdownIsARecoverableInputFailureNotADirectSuccess() {
        DocxRenderTool tool = new DocxRenderTool(null, null);

        ToolInputValidationException error = assertThrows(ToolInputValidationException.class,
                () -> tool.renderDocx("  ", "report", "A4", null));

        assertTrue(error.getMessage().contains("markdown must not be blank"));
    }

    private static void assertReturnDirect(String methodName, Class<?>... parameterTypes) throws Exception {
        Tool tool = DocxRenderTool.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(Tool.class);

        assertTrue(tool.returnDirect(), methodName + " must stop the tool loop after producing a download link");
    }
}
