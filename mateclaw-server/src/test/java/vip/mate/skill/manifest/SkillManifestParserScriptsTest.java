package vip.mate.skill.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SkillManifestParser}'s handling of the {@code scripts}
 * frontmatter block — the typed script entrypoint declarations.
 */
class SkillManifestParserScriptsTest {

    /** parseRawMap / parseFromFrontmatter never touch the frontmatter parser. */
    private final SkillManifestParser parser = new SkillManifestParser(null);

    @Test
    @DisplayName("a scripts block parses into typed ScriptDef entries")
    void parsesScriptsBlock() {
        Map<String, Object> entry = Map.of(
                "id", "create_meeting",
                "label", "Create Meeting",
                "path", "scripts/dispatcher.py",
                "description", "Schedule a meeting",
                "arg_style", "json",
                "fixed_args", List.of("schedule_meeting"),
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of("topic", Map.of("type", "string")),
                        "required", List.of("topic")));
        Map<String, Object> fm = Map.of(
                "name", "demo",
                "type", "code",
                "scripts", List.of(entry));

        SkillManifest manifest = parser.parseRawMap(fm, null, null);

        assertThat(manifest).isNotNull();
        assertThat(manifest.getScripts()).hasSize(1);
        SkillManifest.ScriptDef def = manifest.getScripts().get(0);
        assertThat(def.getId()).isEqualTo("create_meeting");
        assertThat(def.getPath()).isEqualTo("scripts/dispatcher.py");
        assertThat(def.getArgStyle()).isEqualTo("json");
        assertThat(def.getFixedArgs()).containsExactly("schedule_meeting");
        assertThat(def.getParameters()).containsKey("properties");
        // 'scripts' is a known key — it must not also leak into extras.
        assertThat(manifest.getExtras()).doesNotContainKey("scripts");
    }

    @Test
    @DisplayName("arg_style defaults to json when omitted")
    void argStyleDefaults() {
        Map<String, Object> entry = Map.of("id", "run", "path", "scripts/run.sh");
        SkillManifest manifest = parser.parseRawMap(
                Map.of("name", "demo", "scripts", List.of(entry)), null, null);
        assertThat(manifest.getScripts()).hasSize(1);
        assertThat(manifest.getScripts().get(0).getArgStyle()).isEqualTo("json");
    }

    @Test
    @DisplayName("no scripts block yields an empty list, not null")
    void noScriptsBlock() {
        SkillManifest manifest = parser.parseRawMap(Map.of("name", "demo"), null, null);
        assertThat(manifest.getScripts()).isEmpty();
    }
}
