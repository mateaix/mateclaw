package vip.mate.workspace.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the first-run-only owner bootstrap semantics described in RFC-076.
 * Each test runs against a fresh in-memory H2 with the minimum schema needed
 * by {@link WorkspaceSchemaMigration} — no Spring context, no Flyway.
 */
class WorkspaceSchemaMigrationTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbcTemplate;
    private WorkspaceSchemaMigration migration;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("workspace_schema_migration_test_" + UUID.randomUUID())
                .build();
        jdbcTemplate = new JdbcTemplate(db);
        jdbcTemplate.execute("""
                CREATE TABLE mate_user (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64),
                    role VARCHAR(32),
                    deleted INT NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE mate_workspace (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(128),
                    slug VARCHAR(64),
                    description VARCHAR(256),
                    owner_id BIGINT,
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    deleted INT NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE mate_workspace_member (
                    id BIGINT PRIMARY KEY,
                    workspace_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    role VARCHAR(32) NOT NULL,
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    deleted INT NOT NULL DEFAULT 0
                )
                """);
        // Pre-seed the default workspace so ensureDefaultWorkspace() is a no-op
        // and tests can focus on the owner bootstrap branch.
        jdbcTemplate.update(
                "INSERT INTO mate_workspace (id, name, slug, description, owner_id, "
                        + "create_time, update_time, deleted) VALUES (1, 'Default', 'default', "
                        + "'default', NULL, NOW(), NOW(), 0)");
        migration = new WorkspaceSchemaMigration(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    @DisplayName("existing owner: admin is not force-added")
    void existingOwner_skipsBootstrap() {
        insertUser(10, "admin1", "admin");
        insertMember(100, 1, 99, "owner");

        migration.run(new DefaultApplicationArguments());

        assertEquals(1, countMembers(), "membership row count must not change");
        assertEquals(0, countMembersForUser(10), "admin must not be force-added");
    }

    @Test
    @DisplayName("no owner, admin present: lowest-id admin becomes owner")
    void noOwnerWithAdmin_bootstrapsLowestIdAdminAsOwner() {
        insertUser(11, "admin2", "admin");
        insertUser(10, "admin1", "admin");

        migration.run(new DefaultApplicationArguments());

        assertEquals(1, countMembers());
        Long ownerUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM mate_workspace_member WHERE workspace_id = 1 AND role = 'owner'",
                Long.class);
        assertEquals(10L, ownerUserId, "lowest-id admin must be picked");
    }

    @Test
    @DisplayName("no owner, no admin: warns and skips without throwing")
    void noOwnerNoAdmin_skipsAndDoesNotThrow() {
        insertUser(10, "alice", "user");

        assertDoesNotThrow(() -> migration.run(new DefaultApplicationArguments()));

        assertEquals(0, countMembers());
    }

    @Test
    @DisplayName("non-admin owner already present: admin is not force-added")
    void existingNonAdminOwner_skipsBootstrap() {
        insertUser(10, "admin1", "admin");
        insertUser(11, "manualOwner", "user");
        insertMember(100, 1, 11, "owner");

        migration.run(new DefaultApplicationArguments());

        assertEquals(1, countMembers());
        assertEquals(0, countMembersForUser(10), "admin must not be force-added");
    }

    private void insertUser(long id, String username, String role) {
        jdbcTemplate.update(
                "INSERT INTO mate_user (id, username, role, deleted) VALUES (?, ?, ?, 0)",
                id, username, role);
    }

    private void insertMember(long id, long workspaceId, long userId, String role) {
        jdbcTemplate.update(
                "INSERT INTO mate_workspace_member (id, workspace_id, user_id, role, "
                        + "create_time, update_time, deleted) VALUES (?, ?, ?, ?, NOW(), NOW(), 0)",
                id, workspaceId, userId, role);
    }

    private int countMembers() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_workspace_member WHERE deleted = 0", Integer.class);
        return n == null ? 0 : n;
    }

    private int countMembersForUser(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_workspace_member WHERE user_id = ? AND deleted = 0",
                Integer.class, userId);
        return n == null ? 0 : n;
    }
}
