package vip.mate.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vip.mate.MateClawApplication;

/**
 * Abstract base for PostgreSQL end-to-end tests that run against a real
 * PostgreSQL server (Testcontainers), not the in-memory H2 the rest of the
 * suite uses.
 *
 * <p>Why this exists: the {@code db/migration/postgresql} tree carries
 * PostgreSQL-only column types (JSONB) and a JSONB-native rewrite of V53 that
 * H2 can never exercise. Running it on a real server is the only way to prove
 * the 150+ migrations actually apply and that the JSONB columns behave as
 * intended. See {@code docs/database-postgresql.md}.
 *
 * <p>Subclasses get:
 * <ul>
 *   <li>A full Spring Boot context whose datasource + Flyway point at a
 *       throwaway {@code postgres:16-alpine} container, with the
 *       {@code postgresql} migration tree, {@code postgre_sql} MyBatis dialect,
 *       and {@code stringtype=unspecified} (so String-bound JSON values coerce
 *       into JSONB) — mirroring {@code application-postgres.yml}.</li>
 *   <li>The {@code mateclaw} schema pre-created via an init script, matching
 *       the production {@code currentSchema=mateclaw} convention.</li>
 *   <li>{@link DirtiesContext} after each class so a fresh context (and a fresh
 *       container) is used per test class.</li>
 * </ul>
 *
 * <p>{@link Testcontainers#disabledWithoutDocker() disabledWithoutDocker} is
 * set so this test (and subclasses) are <em>skipped</em>, not failed, on
 * machines / CI runners without a Docker daemon. A normal {@code mvn test}
 * therefore stays green everywhere; the PostgreSQL coverage kicks in wherever
 * Docker is available.
 *
 * <p>Subclasses author the actual {@code @Test} methods; this class has none so
 * JUnit's abstract-class discovery skips it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/postgresql",
                "mybatis-plus.global-config.db-config.db-type=postgre_sql",
                // Keep background schedulers quiet during the test context.
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresE2EBaseTest {

    /**
     * Shared across subclasses in the same JVM run. The init script creates the
     * {@code mateclaw} schema before Flyway runs (PostgreSQL won't auto-create a
     * non-public schema), matching the production {@code currentSchema} setup.
     */
    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("mateclaw")
                    .withUsername("test")
                    .withPassword("test")
                    .withInitScript("db/test/init-mateclaw-schema.sql")
                    // currentSchema pins the mateclaw schema (matching prod);
                    // stringtype=unspecified lets the driver coerce String-bound
                    // JSON values into JSONB. Added via withUrlParam so getJdbcUrl()
                    // returns a correctly-formed query string (no double '?').
                    .withUrlParam("currentSchema", "mateclaw")
                    .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // HikariCP belt-and-suspenders: force search_path on every new connection
        // so nothing lands in the public schema even if currentSchema is dropped.
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO mateclaw");
        // Flyway shares the same already-parameterized URL so it creates the
        // schema objects in mateclaw too (consistent with the bootstrap runner).
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
