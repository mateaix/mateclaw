package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SkillScriptTool#normalizeArgs(String)} — the decode
 * step that turns the JSON-encoded {@code args} tool parameter into the
 * positional argument list handed to a skill script.
 *
 * <p>The decisive cases are the ones a model gets wrong when a script needs a
 * JSON payload: the object passed directly, the object wrapped in an array,
 * and the object pre-escaped into an array of one string all have to converge
 * on the same single JSON argument. A bare scalar must survive untouched —
 * decoding {@code 2026-05-19} would otherwise truncate it to {@code 2026}.
 */
class SkillScriptToolArgsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Unused collaborators are null — {@code normalizeArgs} only needs the mapper. */
    private final SkillScriptTool tool =
            new SkillScriptTool(null, null, null, null, objectMapper);

    @Test
    @DisplayName("null / blank args yield no argument list")
    void emptyInputs() {
        assertThat(tool.normalizeArgs(null)).isNull();
        assertThat(tool.normalizeArgs("")).isNull();
        assertThat(tool.normalizeArgs("   ")).isNull();
        assertThat(tool.normalizeArgs("[]")).isNull();
    }

    @Test
    @DisplayName("a JSON object is forwarded as a single JSON argument")
    void objectBecomesOneArg() throws Exception {
        List<String> args = tool.normalizeArgs("{\"date\":\"2026-05-19\",\"topic\":\"智能体\"}");
        assertThat(args).hasSize(1);
        JsonNode parsed = objectMapper.readTree(args.get(0));
        assertThat(parsed.get("date").asText()).isEqualTo("2026-05-19");
        assertThat(parsed.get("topic").asText()).isEqualTo("智能体");
    }

    @Test
    @DisplayName("an object wrapped in a single-element array still reaches the script as JSON")
    void objectWrappedInArray() throws Exception {
        List<String> args = tool.normalizeArgs("[{\"date\":\"x\"}]");
        assertThat(args).hasSize(1);
        assertThat(objectMapper.readTree(args.get(0)).get("date").asText()).isEqualTo("x");
    }

    @Test
    @DisplayName("an object pre-escaped into an array of one string is unwrapped")
    void objectPreEscapedInArray() throws Exception {
        List<String> args = tool.normalizeArgs("[\"{\\\"date\\\":\\\"x\\\"}\"]");
        assertThat(args).hasSize(1);
        assertThat(objectMapper.readTree(args.get(0)).get("date").asText()).isEqualTo("x");
    }

    @Test
    @DisplayName("a plain JSON array maps to one positional argument per element")
    void plainArrayKeepsElements() {
        assertThat(tool.normalizeArgs("[\"--verbose\",\"input.txt\"]"))
                .containsExactly("--verbose", "input.txt");
        assertThat(tool.normalizeArgs("[1,2,3]"))
                .containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("a bare scalar is forwarded verbatim, never JSON-decoded")
    void bareScalarUntouched() {
        // Decoding would truncate this to "2026" — it must survive intact.
        assertThat(tool.normalizeArgs("2026-05-19")).containsExactly("2026-05-19");
        assertThat(tool.normalizeArgs("智能体")).containsExactly("智能体");
        assertThat(tool.normalizeArgs("  hello world  ")).containsExactly("hello world");
    }

    @Test
    @DisplayName("text that looks like JSON but does not parse is forwarded verbatim")
    void malformedJsonForwardedVerbatim() {
        assertThat(tool.normalizeArgs("{bad json")).containsExactly("{bad json");
        assertThat(tool.normalizeArgs("[1,2")).containsExactly("[1,2");
    }
}
