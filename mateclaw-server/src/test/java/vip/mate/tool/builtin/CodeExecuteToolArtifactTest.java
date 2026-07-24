package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * execute_code embeds artifact download links into its result; the chat layer
 * then extracts them (issue #191). This pins the link shape so a JSON-array
 * regression can't sneak back: an array's own '[' sits next to the markdown '['
 * and the extractor would capture '"[name' as the filename.
 */
class CodeExecuteToolArtifactTest {

    // Mirror of ChatController.GENERATED_FILE_LINK_PATTERN.
    private static final Pattern LINK = Pattern.compile(
            "\\[([^\\]]+)\\]\\(((?:https?://[^/\\s)\\]]+)?/api/v1/files/generated/[A-Za-z0-9-]+)\\)");

    @Test
    @DisplayName("formatResult embeds links so extraction yields the clean filename, not '\"[name'")
    void formatResultExtractsCleanFilename() {
        CodeExecuteTool tool = new CodeExecuteTool(null, null, null, null, null, null);
        var result = vip.mate.skill.runtime.SkillScriptExecutionService.ScriptResult.error(0, "");
        String out = tool.formatResult(result, List.of(
                "[report.csv](http://localhost:18088/api/v1/files/generated/abc-123)",
                "[data.xlsx](http://localhost:18088/api/v1/files/generated/def-456)"));

        List<String> names = new ArrayList<>();
        Matcher m = LINK.matcher(out);
        while (m.find()) {
            names.add(m.group(1));
        }
        assertEquals(List.of("report.csv", "data.xlsx"), names,
                "extracted filenames must be clean, full result was: " + out);
    }

    @Test
    @DisplayName("No artifacts → no generatedFiles field")
    void noArtifactsNoField() {
        CodeExecuteTool tool = new CodeExecuteTool(null, null, null, null, null, null);
        var result = vip.mate.skill.runtime.SkillScriptExecutionService.ScriptResult.error(0, "ok");
        String out = tool.formatResult(result, List.of());
        assertEquals(false, out.contains("generatedFiles"), out);
    }
}
