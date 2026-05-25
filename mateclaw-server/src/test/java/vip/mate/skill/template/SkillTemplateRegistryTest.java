package vip.mate.skill.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-091 — verifies the built-in templates parse cleanly and expose
 * the form fields the wizard expects. Catches regressions where a
 * shipped template.json drifts out of schema.
 */
class SkillTemplateRegistryTest {

    private SkillTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillTemplateRegistry(new ObjectMapper());
        registry.load();
    }

    @Test
    @DisplayName("ships at least one knowledge template and one prompt template")
    void shipsBothTemplateTypes() {
        List<SkillTemplate> all = registry.all();
        assertFalse(all.isEmpty(), "expected at least one shipped template");
        assertTrue(all.stream().anyMatch(t -> "knowledge".equals(t.getType())),
                "expected at least one type=knowledge template");
        assertTrue(all.stream().anyMatch(t -> "prompt".equals(t.getType())),
                "expected at least one type=prompt template");
    }

    @Test
    @DisplayName("starter library hits the RFC-091 §2.1 floor of 10 templates")
    void starterLibraryFloor() {
        // RFC-091 §2.1 期望 10–20 个起步模板。本仓库目前 ship 10 个 v1
        // (tcm-qa / legal-clauses-qa / training-qa / meeting-summarizer /
        //  crm-assistant / weekly-report / email-summarizer / data-analyst-prompt /
        //  codex-coding-helper / claude-code-helper)。若降到 10 以下视为回归。
        assertTrue(registry.all().size() >= 10,
                "starter library should ship >= 10 templates; got " + registry.all().size());
    }

    @Test
    @DisplayName("codex-coding-helper template demonstrates type=acp wiring")
    void codexAcpTemplateShape() {
        SkillTemplate t = registry.find("codex-coding-helper");
        assertNotNull(t, "codex-coding-helper template missing");
        assertEquals("acp", t.getType());
        assertTrue(t.getSkillMd().contains("type: acp"));
        assertTrue(t.getSkillMd().contains("endpoint: codex"));
        assertTrue(t.getFields().stream().anyMatch(f -> "system_prefix".equals(f.getKey())));
    }

    @Test
    @DisplayName("claude-code-helper mirrors codex template wiring with endpoint=claude-code")
    void claudeAcpTemplateShape() {
        SkillTemplate t = registry.find("claude-code-helper");
        assertNotNull(t, "claude-code-helper template missing");
        assertEquals("acp", t.getType());
        assertTrue(t.getSkillMd().contains("type: acp"));
        assertTrue(t.getSkillMd().contains("endpoint: claude-code"));
    }

    @Test
    @DisplayName("legal-clauses-qa template exists, knowledge type, kb-picker present")
    void legalTemplateShape() {
        SkillTemplate t = registry.find("legal-clauses-qa");
        assertNotNull(t);
        assertEquals("knowledge", t.getType());
        assertTrue(t.getFields().stream().anyMatch(f -> "kb-picker".equals(f.getType())));
    }

    @Test
    @DisplayName("data-analyst-prompt template exposes SQL dialect select")
    void dataAnalystTemplateShape() {
        SkillTemplate t = registry.find("data-analyst-prompt");
        assertNotNull(t);
        assertEquals("prompt", t.getType());
        assertTrue(t.getFields().stream().anyMatch(f ->
                "sql_dialect".equals(f.getKey()) && "select".equals(f.getType())));
    }

    @Test
    @DisplayName("tcm-qa template exposes kb-picker + skill_name fields")
    void tcmTemplateShape() {
        SkillTemplate t = registry.find("tcm-qa");
        assertNotNull(t, "tcm-qa template missing");
        assertEquals("knowledge", t.getType());
        assertNotNull(t.getSkillMd());
        assertTrue(t.getSkillMd().contains("{{skill_name}}"));
        assertTrue(t.getSkillMd().contains("{{kb_slug}}"));
        assertTrue(t.getFields().stream().anyMatch(f -> "kb-picker".equals(f.getType())));
        assertTrue(t.getFields().stream().anyMatch(f -> "skill_name".equals(f.getKey()) && f.isRequired()));
    }

    @Test
    @DisplayName("meeting-summarizer is a prompt-only template with no kb-picker")
    void meetingSummarizerShape() {
        SkillTemplate t = registry.find("meeting-summarizer");
        assertNotNull(t);
        assertEquals("prompt", t.getType());
        assertFalse(t.getFields().stream().anyMatch(f -> "kb-picker".equals(f.getType())));
    }
}
