package vip.mate.goal;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in real HTTP approval replay against a disposable MySQL or PostgreSQL database. */
@EnabledIfSystemProperty(named = "mateclaw.json.external.enabled", matches = "true")
class GoalJsonExternalApprovalIntegrationTest extends GoalJsonHttpRuntimeIntegrationTest {
    @org.springframework.boot.test.mock.mockito.MockBean
    private vip.mate.skill.workspace.SkillWorkspaceBootstrapRunner skillWorkspaceBootstrap;

    @Override
    @ParameterizedTest
    @CsvSource({"false,sync,true", "false,approval,true", "true,approval,true",
            "false,queued-unselected-then-goal,true", "true,queued-unselected-then-goal,true",
            "false,scheduled-queued,true", "true,scheduled-queued,true",
            "false,scheduled-queued-foreign,true", "true,scheduled-queued-foreign,true",
            "false,scheduled-queued-legacy,true", "true,scheduled-queued-legacy,true",
            "false,scheduled-queued-legacy-new-goal,true", "true,scheduled-queued-legacy-new-goal,true",
            "false,scheduled-queued-unselected,true", "true,scheduled-queued-unselected,true",
            "false,scheduled-queued-terminal-unselected,true", "true,scheduled-queued-terminal-unselected,true",
            "false,scheduled-queued-paused,true", "true,scheduled-queued-paused,true",
            "false,terminal-approval,true", "true,terminal-approval,true",
            "false,legacy-terminal-approval,true", "true,legacy-terminal-approval,true",
            "false,originless-terminal-approval,true", "true,originless-terminal-approval,true",
            "false,late-terminal-approval,true", "true,late-terminal-approval,true",
            "false,queued-terminal-approval,true", "true,queued-terminal-approval,true",
            "false,queued-revoked-approval,true", "true,queued-revoked-approval,true"})
    void authenticatedGoalCompletesThroughHttpOrScheduledProductionRuntime(
            boolean plan, String entry, boolean accepted) throws Exception {
        super.authenticatedGoalCompletesThroughHttpOrScheduledProductionRuntime(plan, entry, accepted);
    }

    @DynamicPropertySource
    static void externalDatabase(DynamicPropertyRegistry properties) {
        String url = required("url");
        String driver = required("driver");
        String username = required("username");
        String password = required("password");
        String dialect = required("dialect");
        if (!dialect.equals("mysql") && !dialect.equals("kingbase"))
            throw new IllegalArgumentException("External dialect must be mysql or kingbase");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> driver);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.url", () -> url);
        properties.add("spring.flyway.user", () -> username);
        properties.add("spring.flyway.password", () -> password);
        properties.add("spring.flyway.locations", () -> System.getProperty(
                "mateclaw.json.external.migration-location", "classpath:db/migration/" + dialect));
        if (dialect.equals("mysql"))
            properties.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
    }

    private static String required(String name) {
        String value = System.getProperty("mateclaw.json.external." + name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing external database property: " + name);
        return value;
    }
}
