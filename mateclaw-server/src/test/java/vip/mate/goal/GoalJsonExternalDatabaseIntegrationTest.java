package vip.mate.goal;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in replay of the same real-service acceptance contract against a disposable external DB.
 * Supply mateclaw.json.external.{url,driver,username,password,dialect}; never point at user data.
 * Use an empty disposable database. The production Flyway tree runs by default;
 * an explicitly supplied migration-location can isolate unrelated legacy migration defects.
 * Memory and bundled-skill import are mocked; Goal storage, transactions and recipes are real.
 */
@EnabledIfSystemProperty(named = "mateclaw.json.external.enabled", matches = "true")
class GoalJsonExternalDatabaseIntegrationTest extends GoalJsonAcceptanceIntegrationTest {
    // Binary bundled-skill imports are outside this JSON protocol contract.
    @org.springframework.boot.test.mock.mockito.MockBean
    private vip.mate.skill.workspace.SkillWorkspaceBootstrapRunner skillWorkspaceBootstrap;

    @DynamicPropertySource
    static void externalDatabase(DynamicPropertyRegistry properties) {
        String url = required("url");
        String driver = required("driver");
        String username = required("username");
        String password = required("password");
        String dialect = required("dialect");
        if (!dialect.equals("mysql") && !dialect.equals("kingbase")) {
            throw new IllegalArgumentException("External dialect must be mysql or kingbase");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> driver);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.url", () -> url);
        properties.add("spring.flyway.user", () -> username);
        properties.add("spring.flyway.password", () -> password);
        properties.add("spring.flyway.locations", () -> System.getProperty(
                "mateclaw.json.external.migration-location", "classpath:db/migration/" + dialect));
        if (dialect.equals("mysql")) {
            properties.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
        }
    }

    private static String required(String name) {
        String value = System.getProperty("mateclaw.json.external." + name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing external database property: " + name);
        return value;
    }
}
