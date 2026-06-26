package vip.mate.wiki.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import lombok.Data;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Definition of a single pageType within a {@link WikiPageTypeProfile}:
 * a human label, optional description, the metadata field schema, the
 * per-stage LLM instructions and an optional Markdown template.
 *
 * @author MateClaw Team
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WikiPageTypeDef {

    /** Human-readable label, e.g. "Episode". */
    private String label;

    /** Short description of what this page type represents. */
    private String description;

    /**
     * Knowledge layer this page type belongs to: {@code fact} ("what is") or
     * {@code experience} ("what it means"). Extensible — MVP recognises these
     * two; {@code null} means unspecified (treated as fact for retrieval).
     */
    private String layer;

    /** Field name → schema. Insertion order preserved for prompt rendering. */
    private Map<String, WikiFieldSchema> schema = new LinkedHashMap<>();

    /** Optional stage instructions for route / create / merge. */
    private StageInstructions route;
    private StageInstructions create;
    private StageInstructions merge;

    /** Optional Markdown template metadata. */
    private Template template;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonDeserialize(using = WikiPageTypeDef.StageInstructions.Deserializer.class)
    public static class StageInstructions {
        private String instructions;
        /** Optional template key referenced by the create stage. */
        private String template;

        static class Deserializer extends StdDeserializer<StageInstructions> {
            Deserializer() { super(StageInstructions.class); }

            @Override
            public StageInstructions deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                StageInstructions s = new StageInstructions();
                if (p.currentToken() == JsonToken.VALUE_STRING) {
                    s.setInstructions(p.getText());
                } else if (p.currentToken() == JsonToken.START_OBJECT) {
                    while (p.nextToken() != JsonToken.END_OBJECT) {
                        String field = p.currentName();
                        p.nextToken();
                        if ("instructions".equals(field)) s.setInstructions(p.getValueAsString());
                        else if ("template".equals(field)) s.setTemplate(p.getText());
                        else p.skipChildren();
                    }
                }
                return s;
            }
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Template {
        private String key;
        private String markdown;
    }
}
