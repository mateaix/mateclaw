package vip.mate.execution.evidence;

import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.execution.evidence.service.JsonArtifactRecipe;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JsonArtifactRecipeTest {
    private JsonArtifactRecipe.Result check(String json) {
        return JsonArtifactRecipe.check(json.getBytes(StandardCharsets.UTF_8), List.of("report"));
    }
    @Test void checksOnlyNonNullTopLevelFieldsAndNeverGrantsAcceptance() {
        var match = check("{\"report\":false,\"secret\":\"not returned\"}");
        assertEquals("MATCH", match.status());
        assertFalse(match.acceptanceEligible());
        assertEquals("json-required-fields", match.recipeId());
        assertEquals(1, match.recipeRevision());
        assertNotNull(match.checkedAt());
        assertFalse(match.toString().contains("not returned"));
        assertEquals(List.of("report"), check("{\"nested\":{\"report\":1}}").missingFields());
        assertEquals("MISSING_FIELDS", check("{\"report\":null}").status());
    }
    @Test void rejectsAmbiguousMalformedAndExcessiveJson() {
        for (String text : List.of("", "null", "[]", "oops", "{\"report\":1} {}",
                "{\"report\":1,\"report\":2}", "{\"report\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}")) {
            assertEquals("INVALID_JSON", check(text).status(), text);
        }
        assertEquals("UNKNOWN", JsonArtifactRecipe.check(new byte[1_048_577], List.of("report")).status());
    }
    @Test void rejectsEmptyDuplicateAndOversizedRequirements() {
        assertThrows(MateClawException.class, () -> JsonArtifactRecipe.validate(null));
        for (List<String> fields : List.of(List.<String>of(), List.of(""), List.of("x", "x"),
                List.of("a\nb"), List.of("x".repeat(129)), java.util.stream.IntStream.range(0, 17)
                        .mapToObj(i -> "key" + i).toList())) {
            assertEquals(400, assertThrows(MateClawException.class, () -> JsonArtifactRecipe.validate(fields)).getCode());
        }
    }
}
