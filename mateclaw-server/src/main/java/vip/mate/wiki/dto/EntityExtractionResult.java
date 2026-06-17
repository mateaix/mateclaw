package vip.mate.wiki.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured output schema for a single entity-extraction LLM call over one
 * source chunk. Bound via {@code BeanOutputConverter} and tolerant of a model
 * returning either field empty.
 *
 * @author MateClaw Team
 */
@Data
public class EntityExtractionResult {

    /** Named entities found in the chunk. */
    private List<ExtractedEntity> entities = new ArrayList<>();

    /** Subject → predicate → object triples between the extracted entities. */
    private List<ExtractedRelation> relations = new ArrayList<>();

    @Data
    public static class ExtractedEntity {
        /** Canonical surface form of the entity as it appears in the text. */
        private String name;
        /** One of the requested types: person | organization | location | event | product | concept | other. */
        private String type;
        /** Alternate names / spellings for the same entity, if any. */
        private List<String> aliases = new ArrayList<>();
        /** One-line description grounded in the chunk. */
        private String description;
        /** Short verbatim quote evidencing the entity. */
        private String evidence;
    }

    @Data
    public static class ExtractedRelation {
        /** Subject entity name (should match an entry in {@link #entities}). */
        private String subject;
        /** Relation label, e.g. "works_for", "located_in", "founded". */
        private String predicate;
        /** Object entity name (should match an entry in {@link #entities}). */
        private String object;
        /** Short verbatim quote evidencing the relation. */
        private String evidence;
    }
}
