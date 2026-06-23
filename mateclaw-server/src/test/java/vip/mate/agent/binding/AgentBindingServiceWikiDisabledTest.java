package vip.mate.agent.binding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.binding.service.AgentBindingService;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #304 coverage for the {@code wiki_disabled} opt-out flag on
 * {@code mate_agent}. Mirrors the contract proven for {@code skills_disabled}
 * / {@code tools_disabled} in {@link AgentBindingServiceTest}: the flag flips
 * the "no binding rows" semantic from "inherit workspace-wide" to "explicitly
 * scoped to zero KBs", and a non-empty {@code setKbBindings} save auto-clears
 * a stale flag.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:binding_wiki_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class AgentBindingServiceWikiDisabledTest {

    private static final long AGENT_ID = 9_500_011L;
    private static final long KB_ID_A = 9_500_101L;
    private static final long KB_ID_B = 9_500_102L;

    @Autowired private AgentBindingService bindingService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_agent_wiki_kb WHERE agent_id = ?", AGENT_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update("DELETE FROM mate_wiki_knowledge_base WHERE id IN (?, ?)", KB_ID_A, KB_ID_B);

        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, skills_disabled, tools_disabled, wiki_disabled, " +
                        "create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wiki-disabled-agent', 'react', '', 10, TRUE, 1, " +
                        "FALSE, FALSE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        // Two KBs in workspace 1 so we can prove "no rows → inherit" surfaces them
        // and "disabled → Set.of()" hides them, without relying on fixture data.
        for (long kbId : new long[]{KB_ID_A, KB_ID_B}) {
            jdbc.update("MERGE INTO mate_wiki_knowledge_base (id, name, description, status, " +
                            "page_count, raw_count, workspace_id, create_time, update_time, deleted) " +
                            "KEY(id) VALUES (?, ?, ?, 'active', 0, 0, 1, " +
                            "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                    kbId, "kb-" + kbId, "desc-" + kbId);
        }
    }

    private void setWikiDisabledFlag(boolean value) {
        jdbc.update("UPDATE mate_agent SET wiki_disabled = ? WHERE id = ?", value, AGENT_ID);
    }

    private boolean readWikiDisabledFlag() {
        Boolean v = jdbc.queryForObject(
                "SELECT wiki_disabled FROM mate_agent WHERE id = ?",
                Boolean.class, AGENT_ID);
        return Boolean.TRUE.equals(v);
    }

    @Test
    @DisplayName("issue #304: wiki_disabled=false + no binding rows → null (inherit workspace-wide)")
    void noRowsReturnsNullWhenNotDisabled() {
        // Pre-V154 behavior preserved: an agent with no KB rows and no opt-out
        // flag inherits every KB in the workspace. The webchat picker and wiki
        // tools rely on null meaning "no restriction" to fall through to
        // workspace-wide retrieval.
        assertThat(bindingService.getBoundKbIds(AGENT_ID)).isNull();
    }

    @Test
    @DisplayName("issue #304: wiki_disabled=true → Set.of() (NOT null) even with binding rows")
    void disabledFlagReturnsEmptyEvenWithRows() {
        // Seed a binding row so we can prove the flag wins over row count.
        // Stale (flag + leftover rows) is exactly the contradiction the
        // auto-clear in setKbBindings is designed to prevent; this test pins
        // that getBoundKbIds stays defensive when state drifts.
        jdbc.update("MERGE INTO mate_agent_wiki_kb (id, agent_id, kb_id, enabled, " +
                        "create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                9_500_201L, AGENT_ID, KB_ID_A);
        setWikiDisabledFlag(true);

        Set<Long> result = bindingService.getBoundKbIds(AGENT_ID);

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("issue #304: wiki_disabled=false + binding rows → the explicit allowlist")
    void bindingRowsReturnAllowlistWhenNotDisabled() {
        // Standard three-state contract: non-empty bindings + flag off returns
        // the enabled KB ids, not null.
        jdbc.update("MERGE INTO mate_agent_wiki_kb (id, agent_id, kb_id, enabled, " +
                        "create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                9_500_202L, AGENT_ID, KB_ID_A);
        jdbc.update("MERGE INTO mate_agent_wiki_kb (id, agent_id, kb_id, enabled, " +
                        "create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                9_500_203L, AGENT_ID, KB_ID_B);

        Set<Long> result = bindingService.getBoundKbIds(AGENT_ID);

        assertThat(result).containsExactlyInAnyOrder(KB_ID_A, KB_ID_B);
    }

    @Test
    @DisplayName("issue #304: setKbBindings non-empty save auto-clears a stale wiki_disabled flag")
    void setKbBindingsClearsStaleFlag() {
        // Set up the contradiction: flag on, then operator saves a real
        // binding. setKbBindings must clear the flag — same contract as
        // setSkillBindings / setToolBindings on the skills_disabled and
        // tools_disabled flags.
        setWikiDisabledFlag(true);
        assertThat(readWikiDisabledFlag()).isTrue();

        bindingService.setKbBindings(AGENT_ID, java.util.List.of(KB_ID_A));

        assertThat(readWikiDisabledFlag())
                .as("a concrete KB commitment must clear wiki_disabled so the data layer never holds both states at once")
                .isFalse();
        // And the flag clear surfaces in getBoundKbIds — the new binding is
        // honored, not silently masked by a stale opt-out.
        assertThat(bindingService.getBoundKbIds(AGENT_ID)).containsExactly(KB_ID_A);
    }

    @Test
    @DisplayName("issue #304: setKbBindings empty save leaves the flag untouched (UI toggle owns the bit)")
    void setKbBindingsEmptySaveLeavesFlagUntouched() {
        // Empty / null save is ambiguous: "uncheck everything" vs "I never
        // had any". The UI opt-out toggle owns the bit, not the binding
        // writer. Mirrors setSkillBindings empty-save semantics so the four-
        // state matrix stays consistent across skill/tool/wiki pickers.
        setWikiDisabledFlag(true);
        bindingService.setKbBindings(AGENT_ID, java.util.List.of());
        assertThat(readWikiDisabledFlag()).isTrue();
        assertThat(bindingService.getBoundKbIds(AGENT_ID)).isEmpty();
    }
}
