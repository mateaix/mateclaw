package vip.mate.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillFileAccessPolicyTest {

    private final SkillFileAccessPolicy policy = new SkillFileAccessPolicy();
    private final Path skillDir = Path.of("/workspace/skills/architecture-diagram");

    @Test
    @DisplayName("allows architecture skill templates")
    void allowsTemplatesDirectory() {
        Path resolved = policy.validateAndResolve(skillDir, "templates/template.html");

        assertEquals(skillDir.resolve("templates/template.html"), resolved);
    }

    @Test
    @DisplayName("still rejects unsupported top-level paths")
    void rejectsUnsupportedTopLevelPaths() {
        assertNull(policy.validateAndResolve(skillDir, "assets/logo.svg"));
    }

    @Test
    @DisplayName("rejects traversal from allowed directories")
    void rejectsTraversal() {
        assertNull(policy.validateAndResolve(skillDir, "templates/../SKILL.md"));
    }
}
