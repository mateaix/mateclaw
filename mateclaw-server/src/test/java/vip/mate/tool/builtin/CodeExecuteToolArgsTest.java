package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CodeExecuteTool#normalizeArgs(String)} — the decode step
 * that turns the JSON-encoded {@code args} tool parameter into the positional
 * argument list passed to the executed code.
 *
 * <p>Unlike a skill script, inline code rarely needs a JSON payload, so the rule
 * is simpler than {@code SkillScriptTool}: a JSON array expands to one argument
 * per element; everything else (including a bare scalar that merely looks
 * numeric) is forwarded verbatim as a single argument.
 */
class CodeExecuteToolArgsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Unused collaborators are null — {@code normalizeArgs} only needs the mapper. */
    private final CodeExecuteTool tool =
            new CodeExecuteTool(null, null, null, objectMapper);

    @Test
    @DisplayName("null / blank / empty-array args yield no argument list")
    void emptyInputs() {
        assertThat(tool.normalizeArgs(null)).isNull();
        assertThat(tool.normalizeArgs("")).isNull();
        assertThat(tool.normalizeArgs("   ")).isNull();
        assertThat(tool.normalizeArgs("[]")).isNull();
    }

    @Test
    @DisplayName("a JSON array maps to one positional argument per element")
    void arrayKeepsElements() {
        assertThat(tool.normalizeArgs("[\"--verbose\",\"input.txt\"]"))
                .containsExactly("--verbose", "input.txt");
        assertThat(tool.normalizeArgs("[1,2,3]"))
                .containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("a bare scalar is forwarded verbatim, never JSON-decoded")
    void bareScalarUntouched() {
        assertThat(tool.normalizeArgs("2026-05-19")).containsExactly("2026-05-19");
        assertThat(tool.normalizeArgs("  hello world  ")).containsExactly("hello world");
    }

    @Test
    @DisplayName("text that looks like a JSON array but does not parse is forwarded verbatim")
    void malformedArrayForwardedVerbatim() {
        assertThat(tool.normalizeArgs("[1,2")).containsExactly("[1,2");
    }
}
