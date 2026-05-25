package vip.mate.skill.runtime.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-090 §14.2 — getEffectiveAllowedTools regression tests.
 *
 * <p>Pinned scenarios:
 *  <ol>
 *    <li>No manifest → empty set (legacy fallback)</li>
 *    <li>Manifest, no features → returns allowed-tools wholesale</li>
 *    <li>Manifest with READY feature carrying its own tool subset →
 *        only the subset is exposed</li>
 *    <li>Manifest with READY feature using inheritance →
 *        manifest-level allowed-tools surface</li>
 *    <li>Manifest with SETUP_NEEDED feature → its tools stay hidden
 *        (the LLM must not see unavailable capabilities, §10.2 Q8)</li>
 *  </ol>
 */
class ResolvedSkillEffectiveToolsTest {

    @Test
    @DisplayName("no manifest yields empty set")
    void noManifest() {
        ResolvedSkill r = ResolvedSkill.builder().name("legacy").build();
        assertTrue(r.getEffectiveAllowedTools().isEmpty());
    }

    @Test
    @DisplayName("manifest with no features returns allowed-tools wholesale")
    void manifestNoFeaturesReturnsAllAllowedTools() {
        SkillManifest manifest = SkillManifest.builder()
                .name("simple")
                .allowedTools(List.of("web_search", "file_read"))
                .build();
        ResolvedSkill r = ResolvedSkill.builder()
                .name("simple")
                .manifest(manifest)
                .build();
        assertEquals(Set.of("web_search", "file_read"), r.getEffectiveAllowedTools());
    }

    @Test
    @DisplayName("READY feature with its own tools narrows surface")
    void readyFeatureWithOwnToolsSubset() {
        SkillManifest manifest = SkillManifest.builder()
                .name("clip")
                .allowedTools(List.of("web_search", "shell_exec", "file_read"))
                .features(List.of(
                        SkillManifest.FeatureDef.builder()
                                .id("trim_video").tools(List.of("shell_exec")).build()))
                .build();
        ResolvedSkill r = ResolvedSkill.builder()
                .manifest(manifest)
                .featureStatuses(Map.of("trim_video", "READY"))
                .activeFeatures(Set.of("trim_video"))
                .build();
        assertEquals(Set.of("shell_exec"), r.getEffectiveAllowedTools());
    }

    @Test
    @DisplayName("READY feature with empty tools inherits manifest-level allowed-tools")
    void readyFeatureInheritsAllowedTools() {
        SkillManifest manifest = SkillManifest.builder()
                .name("clip")
                .allowedTools(List.of("web_search", "shell_exec"))
                .features(List.of(
                        SkillManifest.FeatureDef.builder().id("default").build()))
                .build();
        ResolvedSkill r = ResolvedSkill.builder()
                .manifest(manifest)
                .featureStatuses(Map.of("default", "READY"))
                .activeFeatures(Set.of("default"))
                .build();
        assertEquals(Set.of("web_search", "shell_exec"), r.getEffectiveAllowedTools());
    }

    @Test
    @DisplayName("SETUP_NEEDED feature stays hidden from advertisement")
    void setupNeededFeatureHidden() {
        SkillManifest manifest = SkillManifest.builder()
                .name("clip")
                .allowedTools(List.of("file_read"))
                .features(List.of(
                        SkillManifest.FeatureDef.builder()
                                .id("trim_video").tools(List.of("shell_exec")).build(),
                        SkillManifest.FeatureDef.builder()
                                .id("captions").tools(List.of("ai_caption")).build()))
                .build();
        ResolvedSkill r = ResolvedSkill.builder()
                .manifest(manifest)
                .featureStatuses(Map.of("trim_video", "READY", "captions", "SETUP_NEEDED"))
                .activeFeatures(Set.of("trim_video"))
                .build();
        Set<String> tools = r.getEffectiveAllowedTools();
        assertTrue(tools.contains("shell_exec"));
        assertFalse(tools.contains("ai_caption"));
    }

    @Test
    @DisplayName("inheritance does not re-expose tools owned by SETUP_NEEDED features")
    void inheritanceFencedAgainstSetupNeededTools() {
        // Two features:
        //  - "trim_video" READY but uses inheritance (empty tools list)
        //  - "captions"   SETUP_NEEDED and explicitly claims `ai_caption`
        // The manifest-level allowed-tools includes both `shell_exec`
        // (general) and `ai_caption` (claimed by captions). The
        // READY-via-inheritance branch must surface shell_exec but
        // NOT re-expose ai_caption.
        SkillManifest manifest = SkillManifest.builder()
                .name("clip")
                .allowedTools(List.of("shell_exec", "ai_caption"))
                .features(List.of(
                        SkillManifest.FeatureDef.builder()
                                .id("trim_video")
                                .build(), // empty tools → inherits
                        SkillManifest.FeatureDef.builder()
                                .id("captions")
                                .tools(List.of("ai_caption"))
                                .build()))
                .build();
        ResolvedSkill r = ResolvedSkill.builder()
                .manifest(manifest)
                .featureStatuses(Map.of(
                        "trim_video", "READY",
                        "captions", "SETUP_NEEDED"))
                .activeFeatures(Set.of("trim_video"))
                .build();
        Set<String> tools = r.getEffectiveAllowedTools();
        assertTrue(tools.contains("shell_exec"));
        assertFalse(tools.contains("ai_caption"),
                "inheritance must NOT re-expose tools claimed by a SETUP_NEEDED feature");
    }

    @Test
    @DisplayName("hasAnyActiveFeature reflects activeFeatures set")
    void hasAnyActiveFeatureFlag() {
        ResolvedSkill empty = ResolvedSkill.builder().build();
        assertFalse(empty.hasAnyActiveFeature());

        ResolvedSkill withActive = ResolvedSkill.builder()
                .activeFeatures(Set.of("default"))
                .build();
        assertTrue(withActive.hasAnyActiveFeature());
    }
}
