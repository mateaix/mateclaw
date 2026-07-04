package vip.mate.skill.manifest;

import org.junit.jupiter.api.Test;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * White-box test: verifies that {@link SkillManifestParser} now populates the
 * {@code constraints} field from YAML frontmatter. Before the fix, the field
 * was declared on {@link SkillManifest} but the parser never called
 * {@code .constraints(...)} on the builder, so B2 (pinSkillConstraints) and
 * agent-4 (catalog anchor) were dead code.
 */
class SkillManifestConstraintsParsingTest {

    private final SkillManifestParser parser = new SkillManifestParser(new SkillFrontmatterParser());

    @Test
    void constraintsBlockPopulatesField() {
        String skillMd = """
                ---
                name: my-skill
                description: A skill with constraints
                constraints:
                  - Never delete files outside /workspace
                  - Always confirm before running shell commands
                  - Use read_file before write_file
                ---
                # My Skill
                Body content here.
                """;

        SkillManifest manifest = parser.parse(skillMd);

        assertThat(manifest).isNotNull();
        assertThat(manifest.getConstraints())
                .hasSize(3)
                .containsExactly(
                        "Never delete files outside /workspace",
                        "Always confirm before running shell commands",
                        "Use read_file before write_file");
    }

    @Test
    void noConstraintsYieldsEmptyList() {
        String skillMd = """
                ---
                name: simple-skill
                description: A skill without constraints
                ---
                # Simple Skill
                """;

        SkillManifest manifest = parser.parse(skillMd);

        assertThat(manifest).isNotNull();
        assertThat(manifest.getConstraints()).isEmpty();
    }

    @Test
    void singleStringConstraintWrapsIntoOneElementList() {
        String skillMd = """
                ---
                name: single-constraint-skill
                constraints: "Always be polite"
                ---
                # Single Constraint Skill
                """;

        SkillManifest manifest = parser.parse(skillMd);

        assertThat(manifest).isNotNull();
        assertThat(manifest.getConstraints()).hasSize(1);
        assertThat(manifest.getConstraints().get(0)).isEqualTo("Always be polite");
    }

    @Test
    void constraintsNotLeakedIntoExtras() {
        String skillMd = """
                ---
                name: extras-test
                constraints:
                  - Rule A
                ---
                # Extras Test
                """;

        SkillManifest manifest = parser.parse(skillMd);

        // constraints should be a typed field, NOT in extras
        assertThat(manifest.getExtras()).doesNotContainKey("constraints");
    }

    @Test
    void constraintsSurviveRoundTripThroughBuilder() {
        SkillManifest original = SkillManifest.builder()
                .name("round-trip")
                .constraints(java.util.List.of("Rule 1", "Rule 2"))
                .build();

        // Rebuild from the same values — verifies the field is wired correctly
        SkillManifest rebuilt = SkillManifest.builder()
                .name(original.getName())
                .constraints(original.getConstraints())
                .build();

        assertThat(rebuilt.getConstraints()).isEqualTo(original.getConstraints());
    }
}
