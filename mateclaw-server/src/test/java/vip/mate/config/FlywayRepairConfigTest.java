package vip.mate.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayRepairConfigTest {
    @TempDir Path migrations;

    private String databaseUrl() {
        return "jdbc:h2:mem:flyway-guard-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private Flyway flyway(String url) {
        return Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:" + migrations.toAbsolutePath())
                .validateOnMigrate(true).load();
    }

    private ApplicationContextRunner context(String url) {
        return new ApplicationContextRunner().withUserConfiguration(FlywayRepairConfig.class)
                .withBean(Flyway.class, () -> flyway(url));
    }

    private int historyChecksum(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT \"checksum\" FROM \"flyway_schema_history\" WHERE \"version\" = '1'")) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    @Test
    void defaultInitializerMigratesFreshDatabaseAndRepeatedStartupIsIdempotent() throws Exception {
        Files.writeString(migrations.resolve("V1__fixture.sql"), "CREATE TABLE upgrade_fixture(id INT PRIMARY KEY);\n");
        String url = databaseUrl();
        context(url).run(started -> assertThat(started).hasNotFailed());
        int appliedChecksum = historyChecksum(url);
        context(url).run(started -> assertThat(started).hasNotFailed());
        assertThat(historyChecksum(url)).isEqualTo(appliedChecksum);
        assertThat(flyway(url).info().applied()).hasSize(1);
        assertThat(flyway(url).migrate().migrationsExecuted).isZero();
    }

    @Test
    void defaultInitializerRejectsChangedAppliedSqlWithoutRepairingHistory() throws Exception {
        Path script = migrations.resolve("V1__fixture.sql");
        Files.writeString(script, "CREATE TABLE upgrade_fixture(id INT PRIMARY KEY);\n");
        String url = databaseUrl();
        context(url).run(started -> assertThat(started).hasNotFailed());
        int appliedChecksum = historyChecksum(url);
        Files.writeString(script, "CREATE TABLE upgrade_fixture(id INT PRIMARY KEY, changed_column INT);\n");
        Files.writeString(migrations.resolve("V2__must_not_run.sql"), "CREATE TABLE should_not_exist(id INT);\n");

        context(url).run(started -> {
            assertThat(started).hasFailed();
            assertThat(started.getStartupFailure()).hasStackTraceContaining("Migration checksum mismatch for migration version 1");
        });
        assertThat(historyChecksum(url)).isEqualTo(appliedChecksum);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SHOULD_NOT_EXIST'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    @Test
    void explicitRepairOptInIsRequiredToChangeHistory() throws Exception {
        Path script = migrations.resolve("V1__fixture.sql");
        Files.writeString(script, "CREATE TABLE upgrade_fixture(id INT PRIMARY KEY);\n");
        String url = databaseUrl();
        context(url).run(started -> assertThat(started).hasNotFailed());
        int original = historyChecksum(url);
        Files.writeString(script, "-- audited comment change\nCREATE TABLE upgrade_fixture(id INT PRIMARY KEY);\n");
        context(url).withPropertyValues("mateclaw.flyway.auto-repair=true")
                .run(started -> assertThat(started).hasNotFailed());
        assertThat(historyChecksum(url)).isNotEqualTo(original);
        assertThat(flyway(url).validateWithResult().validationSuccessful).isTrue();
    }

    private ApplicationContextRunner profileContext(String url, String profile) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FlywayRepairConfig.class, TestDatabaseConfiguration.class)
                .withPropertyValues("spring.profiles.active=" + profile,
                        "test.database-url=" + url,
                        "test.migration-location=filesystem:" + migrations.toAbsolutePath());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDatabaseConfiguration {
        @Bean
        Flyway flyway(Environment environment) {
            // Use real application/profile configuration for validation, but an
            // isolated H2 fixture: no profile's external datasource is opened.
            return Flyway.configure()
                    .dataSource(environment.getRequiredProperty("test.database-url"), "sa", "")
                    .locations(environment.getRequiredProperty("test.migration-location"))
                    .validateOnMigrate(environment.getRequiredProperty(
                            "spring.flyway.validate-on-migrate", Boolean.class))
                    .load();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "mysql", "postgres", "kingbase"})
    void allShippedProfilesRejectChecksumDrift(String profile) throws Exception {
        Path script = migrations.resolve("V1__fixture.sql");
        Files.writeString(script, "CREATE TABLE profile_fixture(id INT PRIMARY KEY);\n");
        String url = databaseUrl();
        profileContext(url, profile).run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBean(Flyway.class).getConfiguration().isValidateOnMigrate()).isTrue();
        });
        int original = historyChecksum(url);
        Files.writeString(script, "CREATE TABLE profile_fixture(id INT PRIMARY KEY, changed_column INT);\n");
        profileContext(url, profile).run(started -> {
            assertThat(started).hasFailed();
            assertThat(started.getStartupFailure()).hasStackTraceContaining(
                    "Migration checksum mismatch for migration version 1");
        });
        assertThat(historyChecksum(url)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "mysql", "postgres", "kingbase"})
    void explicitValidationOverrideRemainsAvailable(String profile) throws Exception {
        Files.writeString(migrations.resolve("V1__fixture.sql"),
                "CREATE TABLE profile_fixture(id INT PRIMARY KEY);\n");
        profileContext(databaseUrl(), profile)
                .withPropertyValues("spring.flyway.validate-on-migrate=false")
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    assertThat(started.getBean(Flyway.class).getConfiguration().isValidateOnMigrate()).isFalse();
                });
    }


    @Test
    void documentedEnvironmentOptInRepairsReviewedHistoryChange() throws Exception {
        Path script = migrations.resolve("V1__fixture.sql");
        Files.writeString(script, "CREATE TABLE profile_fixture(id INT PRIMARY KEY);\n");
        String url = databaseUrl();
        profileContext(url, "default").run(started -> assertThat(started).hasNotFailed());
        int original = historyChecksum(url);
        Files.writeString(script, "-- reviewed comment change\nCREATE TABLE profile_fixture(id INT PRIMARY KEY);\n");
        profileContext(url, "default")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment",
                                Map.of("MATECLAW_FLYWAY_AUTOREPAIR", "true"))))
                .run(started -> assertThat(started).hasNotFailed());
        assertThat(historyChecksum(url)).isNotEqualTo(original);
        assertThat(flyway(url).validateWithResult().validationSuccessful).isTrue();
    }

}
