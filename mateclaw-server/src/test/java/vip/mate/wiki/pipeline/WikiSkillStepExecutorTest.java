package vip.mate.wiki.pipeline;

import org.junit.jupiter.api.Test;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiSkillStepExecutor}: returns the skill's content
 * when allowed, and refuses disabled / scan-failed skills and script execution.
 */
class WikiSkillStepExecutorTest {

    private SkillEntity skill(String name, Boolean enabled, String scan, String content) {
        SkillEntity s = new SkillEntity();
        s.setName(name);
        s.setEnabled(enabled);
        s.setSecurityScanStatus(scan);
        s.setSkillContent(content);
        return s;
    }

    private WikiSkillStepExecutor executor(SkillEntity skill) {
        SkillService service = mock(SkillService.class);
        when(service.findByName("wiki-link-enrich")).thenReturn(skill);
        return new WikiSkillStepExecutor(service);
    }

    private WikiStepContext ctx(Map<String, Object> config) {
        return new WikiStepContext(1L, 42L, "s1", config, "prior");
    }

    @Test
    void returnsSkillContent_whenAllowed() throws Exception {
        WikiSkillStepExecutor e = executor(skill("wiki-link-enrich", true, "PASSED", "do the thing"));
        assertEquals("do the thing", e.execute(ctx(Map.of("skill", "wiki-link-enrich"))));
    }

    @Test
    void missingSkillName_throws() {
        WikiSkillStepExecutor e = executor(skill("wiki-link-enrich", true, "PASSED", "x"));
        assertThrows(IllegalArgumentException.class, () -> e.execute(ctx(Map.of())));
    }

    @Test
    void unknownSkill_throws() {
        WikiSkillStepExecutor e = executor(skill("wiki-link-enrich", true, "PASSED", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> e.execute(ctx(Map.of("skill", "does-not-exist"))));
    }

    @Test
    void disabledSkill_throws() {
        WikiSkillStepExecutor e = executor(skill("wiki-link-enrich", false, "PASSED", "x"));
        assertThrows(IllegalStateException.class,
                () -> e.execute(ctx(Map.of("skill", "wiki-link-enrich"))));
    }

    @Test
    void scanFailedSkill_throws() {
        WikiSkillStepExecutor e = executor(skill("wiki-link-enrich", true, "FAILED", "x"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> e.execute(ctx(Map.of("skill", "wiki-link-enrich"))));
        assertTrue(ex.getMessage().contains("security scan"));
    }

    @Test
    void scriptExecutionRequest_isRefused() {
        WikiSkillStepExecutor e = executor(skill("wiki-link-enrich", true, "PASSED", "x"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> e.execute(ctx(Map.of("skill", "wiki-link-enrich", "run_script", true))));
        assertTrue(ex.getMessage().contains("Script execution is not permitted"));
    }
}
