package vip.mate.wiki.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/**
 * Abstract base for wiki end-to-end tests.
 *
 * <p>Subclasses get:
 * <ul>
 *   <li>A full Spring Boot context with H2 backed by Flyway migrations
 *       (default profile already wires this in {@code application.yml}).</li>
 *   <li>An in-memory H2 database, unique per context build (see
 *       {@link #overrideDatasource}) — not the persistent {@code ./data/mateclaw}
 *       file the default profile points at, which every E2E run used to write
 *       test rows into.</li>
 *   <li>A {@link ChatModel} bean replaced by {@link MockLlmChatModel} loaded
 *       from {@code classpath:fixtures/llm-responses.json}, so tests can
 *       exercise the compile pipeline without hitting a real LLM.</li>
 *   <li>{@link DirtiesContext} after each class so test data does not
 *       leak between {@code @Test}-classes.</li>
 * </ul>
 *
 * <p>Subclasses author actual {@code @Test} methods; this class intentionally
 * has none so it does not produce a "no runnable methods" failure when the
 * test runner picks it up via classpath scanning. Per JUnit 5 rules,
 * abstract test classes are skipped at discovery.
 *
 * @author MateClaw Team
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/h2",
        "mateclaw.feature-flag.refresh-ms=999999"  // disable background refresh during tests
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class WikiE2EBaseTest {

    /**
     * A plain {@code @SpringBootTest(properties=...)} entry must be a compile-time
     * constant, so it can't carry a per-run-unique db name — this runs once per
     * context build instead (paired with {@code @DirtiesContext(AFTER_CLASS)}, that's
     * once per test class), generating a fresh in-memory database name each time so
     * classes never share — let alone pollute — each other's data.
     */
    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        String dbName = "wiki_e2e_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + dbName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
    }

    /** Overrides the default {@link ChatModel} bean with a fixture-driven mock. */
    @TestConfiguration
    public static class MockLlmConfig {

        @Bean
        @Primary
        public ChatModel mockChatModel() {
            return MockLlmChatModel.fromClasspath("/fixtures/llm-responses.json");
        }
    }
}
