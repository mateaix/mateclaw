package vip.mate.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class H2FileDataSourceProtectionTest {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "vip.mate.config", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "vip.mate.config.H2FileDataSourceProtection"))
    static class ProtectionScan { }

    private ApplicationContextRunner runner(String url) {
        return new ApplicationContextRunner().withUserConfiguration(ProtectionScan.class)
                .withBean("dataSource", HikariDataSource.class, () -> {
                    var source = new HikariDataSource();
                    source.setJdbcUrl(url);
                    source.setUsername("sa");
                    source.setMaximumPoolSize(2);
                    source.setMinimumIdle(0);
                    return source;
                });
    }

    @Test
    void interruptedToolWriteCannotCloseDatabaseForOtherConnections() {
        String url = "jdbc:h2:file:" + directory.resolve("interrupt") + ";WRITE_DELAY=0";
        runner(url).run(context -> {
            var source = context.getBean(HikariDataSource.class);
            try (var stable = source.getConnection(); var worker = source.getConnection();
                 var executor = Executors.newSingleThreadExecutor()) {
                stable.createStatement().execute("CREATE TABLE sample(id INT, body VARCHAR)");
                var failure = new AtomicReference<Exception>();
                executor.submit(() -> {
                    try (var statement = worker.createStatement()) {
                        Thread.currentThread().interrupt();
                        statement.execute("INSERT INTO sample VALUES(1, SPACE(262144))");
                        statement.execute("CHECKPOINT SYNC");
                    } catch (Exception e) {
                        failure.set(e);
                    } finally {
                        Thread.interrupted();
                    }
                }).get(10, TimeUnit.SECONDS);

                try (var statement = stable.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM sample")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
                assertNull(failure.get(), "An interrupted write must recover its file channel");
            }
        });
    }

    @Test
    void protectedUrlOpensExistingDatabaseAndPreservesOptions() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("existing")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker(id INT PRIMARY KEY)");
            statement.execute("INSERT INTO marker VALUES(681)");
        }
        runner(url).run(context -> {
            var source = context.getBean(HikariDataSource.class);
            assertEquals(url.replace("jdbc:h2:", "jdbc:h2:retry:"), source.getJdbcUrl());
            try (var connection = source.getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT id FROM marker")) {
                assertTrue(rows.next());
                assertEquals(681, rows.getInt(1));
            }
        });
    }

    @Test
    void bootConfiguredFileUrlIsProtectedBeforeThePoolStarts() {
        String url = "jdbc:h2:file:" + directory.resolve("configured") + ";MODE=MySQL";
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(ProtectionScan.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=sa")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var source = context.getBean(HikariDataSource.class);
                    assertEquals(url.replace("jdbc:h2:", "jdbc:h2:retry:"), source.getJdbcUrl());
                    try (var connection = source.getConnection()) {
                        assertTrue(connection.isValid(1));
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:./data/test", "jdbc:h2:../data/test", "jdbc:h2:/srv/data/test",
            "jdbc:h2:~/data/test", "jdbc:h2:C:/data/test", "jdbc:h2:C:\\data\\test", "jdbc:h2:nio:./data/test"})
    void alternateDiskPathsRetainTheirOriginalLocation(String url) {
        runner(url).run(context -> assertEquals(url.replace("jdbc:h2:", "jdbc:h2:retry:"),
                context.getBean(HikariDataSource.class).getJdbcUrl()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:test", "jdbc:h2:tcp://localhost/test", "jdbc:h2:ssl://localhost/test",
            "jdbc:h2:retry:file:./data/test", "jdbc:mysql://localhost/test", "jdbc:postgresql://localhost/test"})
    void otherStorageAndServerUrlsAreUnchanged(String url) {
        runner(url).run(context -> assertEquals(url, context.getBean(HikariDataSource.class).getJdbcUrl()));
    }
}
