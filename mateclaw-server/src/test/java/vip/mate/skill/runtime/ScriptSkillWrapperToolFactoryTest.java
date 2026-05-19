package vip.mate.skill.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScriptSkillWrapperToolFactory} — the argument
 * translation ({@code buildArgv}) and wrapper naming ({@code wrapperNames})
 * that turn a declared script entrypoint into a typed tool. The model fills
 * schema fields; these are the steps that carry that typed input into the
 * script process without the model hand-crafting a JSON string.
 */
class ScriptSkillWrapperToolFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** wrapperNames touches no collaborators — null deps are fine here. */
    private final ScriptSkillWrapperToolFactory factory =
            new ScriptSkillWrapperToolFactory(null, null, null, objectMapper);

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("json style forwards the whole object as one compact JSON argument")
    void jsonStyleSingleArg() {
        List<String> argv = ScriptSkillWrapperToolFactory.buildArgv(
                List.of(), "json", json("{\"date\":\"2026-05-19\",\"topic\":\"智能体\"}"));
        assertThat(argv).hasSize(1);
        JsonNode back = json(argv.get(0));
        assertThat(back.get("date").asText()).isEqualTo("2026-05-19");
        assertThat(back.get("topic").asText()).isEqualTo("智能体");
    }

    @Test
    @DisplayName("json style with an empty / absent object yields no arguments")
    void jsonStyleEmpty() {
        assertThat(ScriptSkillWrapperToolFactory.buildArgv(List.of(), "json", json("{}"))).isNull();
        assertThat(ScriptSkillWrapperToolFactory.buildArgv(List.of(), "json", null)).isNull();
        assertThat(ScriptSkillWrapperToolFactory.buildArgv(List.of(), null, json("{}"))).isNull();
    }

    @Test
    @DisplayName("flags style emits --key value pairs and drops false / null properties")
    void flagsStyle() {
        List<String> argv = ScriptSkillWrapperToolFactory.buildArgv(List.of(), "flags",
                json("{\"verbose\":true,\"file\":\"in.txt\",\"count\":3,\"debug\":false,\"note\":null}"));
        assertThat(argv).containsExactly("--verbose", "--file", "in.txt", "--count", "3");
    }

    @Test
    @DisplayName("flags style with no usable properties yields no arguments")
    void flagsStyleEmpty() {
        assertThat(ScriptSkillWrapperToolFactory.buildArgv(List.of(), "flags", json("{}"))).isNull();
        assertThat(ScriptSkillWrapperToolFactory.buildArgv(List.of(), "flags", json("{\"off\":false}"))).isNull();
    }

    @Test
    @DisplayName("fixedArgs are emitted before the typed JSON argument")
    void fixedArgsPrependDispatcherMethod() {
        // A dispatcher script: argv[1] = method, argv[2] = JSON payload.
        List<String> argv = ScriptSkillWrapperToolFactory.buildArgv(
                List.of("schedule_meeting"), "json", json("{\"subject\":\"智能体\"}"));
        assertThat(argv).hasSize(2);
        assertThat(argv.get(0)).isEqualTo("schedule_meeting");
        assertThat(json(argv.get(1)).get("subject").asText()).isEqualTo("智能体");
    }

    @Test
    @DisplayName("fixedArgs survive even when the entrypoint takes no typed input")
    void fixedArgsWithoutTypedArgs() {
        assertThat(ScriptSkillWrapperToolFactory.buildArgv(
                List.of("convert_timestamp"), "json", json("{}")))
                .containsExactly("convert_timestamp");
        assertThat(ScriptSkillWrapperToolFactory.buildArgv(
                List.of("m"), "flags", json("{\"v\":true}")))
                .containsExactly("m", "--v");
    }

    @Test
    @DisplayName("wrapperNames builds skill_<slug>_<id> for each usable entrypoint")
    void wrapperNames() {
        SkillManifest manifest = SkillManifest.builder()
                .name("Tencent Meeting")
                .scripts(List.of(
                        SkillManifest.ScriptDef.builder()
                                .id("create_meeting").path("scripts/create.py").build(),
                        SkillManifest.ScriptDef.builder()
                                .id("cancel_meeting").path("scripts/cancel.py").build()))
                .build();
        assertThat(factory.wrapperNames(manifest))
                .containsExactly("skill_tencent_meeting_create_meeting",
                        "skill_tencent_meeting_cancel_meeting");
    }

    @Test
    @DisplayName("wrapperNames skips entrypoints missing an id or a script path")
    void wrapperNamesSkipsIncomplete() {
        SkillManifest manifest = SkillManifest.builder()
                .name("demo")
                .scripts(List.of(
                        SkillManifest.ScriptDef.builder().id("ok").path("scripts/ok.py").build(),
                        SkillManifest.ScriptDef.builder().id("no_path").build(),
                        SkillManifest.ScriptDef.builder().path("scripts/no_id.py").build()))
                .build();
        assertThat(factory.wrapperNames(manifest)).containsExactly("skill_demo_ok");
    }
}
