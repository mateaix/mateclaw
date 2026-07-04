package vip.mate.wiki.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Deserialization tests for {@link WikiPageTypeDef.StageInstructions}: a stage
 * field (route / create / merge) may be written either as a plain prompt string
 * or as a full object with {@code instructions} + {@code template}. Both forms
 * must be equivalent, and existing object configs must keep parsing.
 */
class WikiPageTypeDefStageInstructionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void plainStringShorthandMapsToInstructions() throws Exception {
        WikiPageTypeDef.StageInstructions si =
                mapper.readValue("\"just a prompt\"", WikiPageTypeDef.StageInstructions.class);
        assertEquals("just a prompt", si.getInstructions());
        assertNull(si.getTemplate());
    }

    @Test
    void fullObjectParsesBothFields() throws Exception {
        WikiPageTypeDef.StageInstructions si = mapper.readValue(
                "{\"instructions\":\"do X\",\"template\":\"tpl-key\"}",
                WikiPageTypeDef.StageInstructions.class);
        assertEquals("do X", si.getInstructions());
        assertEquals("tpl-key", si.getTemplate());
    }

    @Test
    void unknownFieldInObjectIsSkipped() throws Exception {
        WikiPageTypeDef.StageInstructions si = mapper.readValue(
                "{\"instructions\":\"keep\",\"extra\":{\"nested\":1}}",
                WikiPageTypeDef.StageInstructions.class);
        assertEquals("keep", si.getInstructions());
        assertNull(si.getTemplate());
    }

    @Test
    void stringAndObjectFormsCoexistInsidePageTypeDef() throws Exception {
        // route as the string shorthand, create as the full object — both on one def.
        String json = "{\"route\":\"route prompt\","
                + "\"create\":{\"instructions\":\"create prompt\",\"template\":\"t1\"}}";
        WikiPageTypeDef def = mapper.readValue(json, WikiPageTypeDef.class);
        assertEquals("route prompt", def.getRoute().getInstructions());
        assertNull(def.getRoute().getTemplate());
        assertEquals("create prompt", def.getCreate().getInstructions());
        assertEquals("t1", def.getCreate().getTemplate());
    }
}
