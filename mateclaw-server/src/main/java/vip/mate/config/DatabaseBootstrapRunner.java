package vip.mate.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Database bootstrap runner.
 * <p>
 * Loads seed data after Flyway migrations on every startup.
 * <p>
 * For data.sql (seed data with locale-specific content):
 * <ul>
 *   <li><b>Web/dev mode</b> (default, {@code mateclaw.setup.await-language-selection=false}):
 *       auto-initializes immediately with {@code mateclaw.setup.default-locale} (zh-CN).</li>
 *   <li><b>Desktop mode</b> ({@code mateclaw.setup.await-language-selection=true}):
 *       defers until the user selects a language via {@code POST /api/v1/setup/init}.</li>
 * </ul>
 */
@Slf4j
@Component
@Order(1)
public class DatabaseBootstrapRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    /** Cached flag: true when running on MySQL/MariaDB, false for H2/Kingbase. */
    private volatile Boolean isMySQL;

    /** Cached flag: true when running on KingbaseES. */
    private volatile Boolean isKingbase;

    /** Cached flag: true when running on PostgreSQL. */
    private volatile Boolean isPostgres;

     /** Cached human-readable label of the connected database, e.g. "MySQL" / "H2" / "PostgreSQL". */
    private volatile String databaseLabel;

    /**
     * When true, wait for Desktop splash screen to call /setup/init with chosen language.
     * When false (default), auto-initialize immediately on startup.
     * <p>
     * Desktop sets this via: {@code --mateclaw.setup.await-language-selection=true}
     */
    @Value("${mateclaw.setup.await-language-selection:false}")
    private boolean awaitLanguageSelection;

    /** Default locale for auto-initialization. */
    @Value("${mateclaw.setup.default-locale:zh-CN}")
    private String defaultLocale;

    /** Whether the database has been seeded with data (user table has rows). */
    @Getter
    private volatile boolean initialized = false;

    /** Guards against concurrent init attempts. */
    private final AtomicBoolean initInProgress = new AtomicBoolean(false);

    public DatabaseBootstrapRunner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Schema creation and built-in tool registration are handled by
        // Flyway (db/migration/). New built-in tools must ship as their own
        // Vxx__register_<tool>_tool.sql migration (see V3, V31 for examples).
        if (isDataAlreadySeeded()) {
            initialized = true;
            log.info("Database already initialized, skipping seed data");
            return;
        }

        if (awaitLanguageSelection) {
            // Desktop mode: wait for /api/v1/setup/init
            log.info("Desktop mode: waiting for language selection via /api/v1/setup/init");
        } else {
            // Web/dev mode: auto-initialize immediately
            log.info("Auto-initializing database with default locale: {}", defaultLocale);
            initWithLocale(defaultLocale);
        }
    }

    /**
     * Initialize seed data with the given locale.
     *
     * @param locale "zh-CN" or "en-US"
     * @return true if initialization was performed, false if already initialized or in progress
     */
    public boolean initWithLocale(String locale) {
        if (initialized) {
            log.info("Database already initialized, ignoring init request");
            return false;
        }
        if (!initInProgress.compareAndSet(false, true)) {
            log.info("Initialization already in progress, ignoring concurrent request");
            return false;
        }
        try {
            // Double-check after acquiring the lock
            if (isDataAlreadySeeded()) {
                initialized = true;
                return false;
            }
            String scriptName;
            if (isMySQL()) {
                scriptName = "en-US".equals(locale) ? "db/data-mysql-en.sql" : "db/data-mysql-zh.sql";
            } else if (isKingbase() || isPostgres()) {
                // PostgreSQL-family seed (covers both PostgreSQL and KingbaseES,
                // which share the same ON CONFLICT / SERIAL-free DDL dialect).
                scriptName = "en-US".equals(locale) ? "db/data-kingbase-en.sql" : "db/data-kingbase-zh.sql";
            } else {
                scriptName = "en-US".equals(locale) ? "db/data-en.sql" : "db/data-zh.sql";
            }
            log.info("Initializing database with locale={} using {}", locale, scriptName);
            runScript(scriptName);
            initialized = true;
            log.info("Database initialization completed successfully");
            return true;
        } catch (Exception e) {
            log.error("Failed to initialize database with locale={}", locale, e);
            throw new RuntimeException("Database initialization failed", e);
        } finally {
            initInProgress.set(false);
        }
    }

    private boolean isDataAlreadySeeded() {
        try {
            if (!tableExists("mate_user")) {
                return false;
            }
            Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM mate_user", Integer.class);
            return userCount != null && userCount > 0;
        } catch (Exception e) {
            log.warn("Error checking database state", e);
            return false;
        }
    }

    /**
     * Friendly product name of the currently connected database, e.g. {@code "MySQL"},
     * {@code "PostgreSQL"}, {@code "H2"} or {@code "KingbaseES"}. Read once from JDBC
     * metadata and cached — the connected database never changes at runtime.
     *
     * @return the product name, or {@code "Unknown"} if metadata is unavailable.
     */
    public String getDatabaseLabel() {
        if (databaseLabel == null) {
            try (Connection connection = dataSource.getConnection()) {
                databaseLabel = normalizeDatabaseLabel(connection.getMetaData().getDatabaseProductName());
            } catch (Exception e) {
                log.debug("Failed to read database product name: {}", e.getMessage());
                databaseLabel = "Unknown";
            }
        }
        return databaseLabel;
    }

    /**
     * Maps a raw JDBC product name to a clean, canonical label. Some drivers append
     * version noise to the product name (e.g. KingbaseES reports "KingbaseES V008R006");
     * collapsing on a keyword keeps the displayed label stable across driver versions
     * and consistent with the dialect this runner detects for DDL.
     *
     * @return a canonical label, or {@code "Unknown"} when the product name is absent.
     */
    static String normalizeDatabaseLabel(String product) {
        if (product == null || product.isBlank()) {
            return "Unknown";
        }
        String lower = product.toLowerCase();
        if (lower.contains("kingbase")) {
            // KingbaseES is the product name; show the vendor's Chinese brand name.
            return "人大金仓";
        }
        if (lower.contains("mariadb")) {
            return "MariaDB";
        }
        if (lower.contains("mysql")) {
            return "MySQL";
        }
        if (lower.contains("postgresql")) {
            return "PostgreSQL";
        }
        if (lower.contains("h2")) {
            return "H2";
        }
        return product.trim();
    }

    private boolean tableExists(String tableName) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, tableName.toUpperCase(), null)) {
                if (rs.next()) {
                    return true;
                }
            }
            try (ResultSet rs = metaData.getTables(null, null, tableName.toLowerCase(), null)) {
                return rs.next();
            }
        }
    }

    private boolean isMySQL() {
        if (isMySQL == null) {
            try (Connection connection = dataSource.getConnection()) {
                String dbProduct = connection.getMetaData().getDatabaseProductName().toLowerCase();
                isMySQL = dbProduct.contains("mysql") || dbProduct.contains("mariadb");
                isKingbase = dbProduct.contains("kingbase");
                // KingbaseES reports its own product name ("KingbaseES"), so the
                // postgres check stays mutually exclusive with the kingbase one.
                isPostgres = dbProduct.contains("postgresql") && !isKingbase;
                if (isKingbase) {
                    log.info("Detected database: {} (KingbaseES mode)", dbProduct);
                } else if (isPostgres) {
                    log.info("Detected database: {} (PostgreSQL mode)", dbProduct);
                } else {
                    log.info("Detected database: {} (MySQL mode: {})", dbProduct, isMySQL);
                }
            } catch (Exception e) {
                log.warn("Failed to detect database type, falling back to H2 mode", e);
                isMySQL = false;
                isKingbase = false;
                isPostgres = false;
            }
        }
        return isMySQL;
    }

    private boolean isKingbase() {
        if (isKingbase == null) {
            // Trigger detection
            isMySQL();
        }
        return isKingbase != null && isKingbase;
    }

    private boolean isPostgres() {
        if (isPostgres == null) {
            // Trigger detection
            isMySQL();
        }
        return isPostgres != null && isPostgres;
    }

    private void runScript(String path) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.addScript(new ClassPathResource(path));
        populator.execute(dataSource);
    }
}
