package vip.mate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guards against circular bean dependencies and other wiring mistakes that
 * only surface when Spring actually constructs the full application context —
 * invisible to Mockito-based unit tests, which never build the real bean graph.
 * <p>
 * This test class did not exist before issue #477: a circular dependency
 * (SystemSettingService → PluginManager → ToolRegistry → I18nService →
 * SystemSettingService) was introduced and went undetected by the full
 * per-class unit test suite until a manual {@code spring-boot:run} smoke check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationContextSmokeTest {

    @Test
    void contextLoads() {
        // Intentionally empty: if the ApplicationContext fails to start
        // (missing bean, circular dependency, bad property, etc.), this
        // test fails during Spring's context setup before the test body runs.
    }
}
