package vip.mate.wiki.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

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
    public static class StageInstructions {
        private String instructions;
        /** Optional template key referenced by the create stage. */
        private String template;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Template {
        private String key;
        private String markdown;
    }
}
