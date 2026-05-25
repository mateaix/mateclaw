package vip.mate.wiki.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Abstract base for wiki end-to-end tests.
 *
 * <p>Subclasses get:
 * <ul>
 *   <li>A full Spring Boot context with H2 backed by Flyway migrations
 *       (default profile already wires this in {@code application.yml}).</li>
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
