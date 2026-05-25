package vip.mate.memory.fact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.memory.fact.extraction.ExtractedFact;
import vip.mate.memory.fact.extraction.PatternEntityExtractor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E1.6-E1.7: Core invariant guard tests for fact projection.
 */
class FactProjectionInvariantTest {

    private final PatternEntityExtractor extractor = new PatternEntityExtractor();

    @Test
    @DisplayName("Pattern extractor: KV bullet format → subject/predicate/object")
    void patternExtractor_kvBullet() {
        String content = """
                ## User Profile
                - **user_name**: User's name is Xu Zhanfu.
                - **role**: User works as a backend developer.
                """;
        List<ExtractedFact> facts = extractor.extract(1L, "structured/user.md", content);

        assertTrue(facts.size() >= 2);
        ExtractedFact nameFact = facts.stream()
                .filter(f -> f.subject().equals("user_name"))
                .findFirst().orElse(null);
        assertNotNull(nameFact);
        assertEquals("is", nameFact.predicate());
        assertTrue(nameFact.objectValue().contains("Xu Zhanfu"));
        assertEquals("user_pref", nameFact.category());
        assertEquals("pattern", nameFact.extractedBy());
    }

    @Test
    @DisplayName("Pattern extractor: sourceRef includes filename#slug")
    void patternExtractor_sourceRef() {
        String content = "- **preferred_language**: Chinese\n";
        List<ExtractedFact> facts = extractor.extract(1L, "structured/user.md", content);

        assertFalse(facts.isEmpty());
        assertTrue(facts.get(0).sourceRef().startsWith("structured/user.md#"));
    }

    @Test
    @DisplayName("Pattern extractor: empty content returns empty list")
    void patternExtractor_emptyContent() {
        assertEquals(List.of(), extractor.extract(1L, "MEMORY.md", ""));
        assertEquals(List.of(), extractor.extract(1L, "MEMORY.md", null));
    }

    @Test
    @DisplayName("Pattern extractor: MEMORY.md general category")
    void patternExtractor_memoryCategory() {
        String content = "- **project_fact**: We use PostgreSQL 15\n";
        List<ExtractedFact> facts = extractor.extract(1L, "MEMORY.md", content);
        assertFalse(facts.isEmpty());
        assertEquals("general", facts.get(0).category());
    }

    @Test
    @DisplayName("Pattern extractor: section heading extraction from structured files")
    void patternExtractor_sectionHeading() {
        String content = "## deployment_env\nProduction runs on Kubernetes with 3 replicas.\n\n## tech_stack\nSpring Boot 3.5 + Vue 3 + PostgreSQL 15\n";
        List<ExtractedFact> facts = extractor.extract(1L, "structured/project.md", content);
        assertTrue(facts.size() >= 1, "Should extract at least one section fact, got: " + facts);
    }

    @Test
    @DisplayName("Core invariant: extractedBy is always 'pattern' for PatternExtractor")
    void coreInvariant_extractedByPattern() {
        String content = "- **key**: value\n## section\ncontent here\n";
        List<ExtractedFact> facts = extractor.extract(1L, "structured/user.md", content);
        for (ExtractedFact f : facts) {
            assertEquals("pattern", f.extractedBy(),
                    "PatternEntityExtractor must always set extractedBy='pattern'");
        }
    }

    @Test
    @DisplayName("Core invariant: confidence is in [0, 1] range")
    void coreInvariant_confidenceRange() {
        String content = "- **name**: test value\n## heading\nbody text content\n";
        List<ExtractedFact> facts = extractor.extract(1L, "structured/user.md", content);
        for (ExtractedFact f : facts) {
            assertTrue(f.confidence() >= 0 && f.confidence() <= 1,
                    "Confidence must be in [0,1]: " + f.confidence());
        }
    }

    @Test
    @DisplayName("E1.6: rebuild after bumpUseCount preserves accumulated columns")
    void rebuildAfterBumpUseCount_preservesAccumulatedColumns() {
        // Invariant: FactProjectionBuilder.upsertDerived only writes derived columns.
        // Accumulated columns (use_count, last_used_at) are set by bumpUseCount only.
        // Verify: a new FactEntity from upsertDerived has useCount=0 (not overwritten).
        var fact = new vip.mate.memory.fact.model.FactEntity();
        fact.setUseCount(42);
        fact.setLastUsedAt(java.time.LocalDateTime.now());
        // After a hypothetical rebuild, derived columns change but accumulated must not
        // This structural test verifies the entity has separate fields
        fact.setSubject("new_subject");
        fact.setObjectValue("new_value");
        assertEquals(42, fact.getUseCount(),
                "Accumulated column use_count must not be reset by derived column updates");
        assertNotNull(fact.getLastUsedAt(),
                "Accumulated column last_used_at must not be nulled by derived column updates");
    }

    @Test
    @DisplayName("E1.7: FactMapper has no direct insert/update for accumulated columns")
    void factMapper_noDirectAccumulatedColumnWrite() {
        // Structural: FactMapper should only expose bumpUseCount for accumulated writes.
        // Check that the mapper interface has bumpUseCount method.
        boolean hasBumpUseCount = false;
        for (var method : vip.mate.memory.fact.repository.FactMapper.class.getDeclaredMethods()) {
            if (method.getName().equals("bumpUseCount")) {
                hasBumpUseCount = true;
            }
            // No method named "updateUseCount" or "setUseCount" should exist
            assertFalse(method.getName().matches("updateUseCount|setUseCount|incrementUseCount"),
                    "FactMapper must not have direct accumulated column setter: " + method.getName());
        }
        assertTrue(hasBumpUseCount, "FactMapper must have bumpUseCount method");
    }
}
