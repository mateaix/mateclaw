package vip.mate.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DatabaseBootstrapRunner#normalizeDatabaseLabel(String)}.
 * Verifies that raw JDBC product names — including version-suffixed ones from the
 * KingbaseES / PostgreSQL family — collapse to a clean, canonical label.
 */
class DatabaseBootstrapRunnerLabelTest {

    @Test
    @DisplayName("KingbaseES product name (with version noise) → '人大金仓'")
    void kingbaseNormalizes() {
        assertEquals("人大金仓", DatabaseBootstrapRunner.normalizeDatabaseLabel("KingbaseES"));
        assertEquals("人大金仓", DatabaseBootstrapRunner.normalizeDatabaseLabel("KingbaseES V008R006"));
        assertEquals("人大金仓", DatabaseBootstrapRunner.normalizeDatabaseLabel("kingbasees"));
    }

    @Test
    @DisplayName("MySQL / MariaDB → canonical labels")
    void mysqlFamilyNormalizes() {
        assertEquals("MySQL", DatabaseBootstrapRunner.normalizeDatabaseLabel("MySQL"));
        assertEquals("MariaDB", DatabaseBootstrapRunner.normalizeDatabaseLabel("MariaDB"));
    }

    @Test
    @DisplayName("PostgreSQL and H2 → canonical labels")
    void postgresAndH2Normalize() {
        assertEquals("PostgreSQL", DatabaseBootstrapRunner.normalizeDatabaseLabel("PostgreSQL"));
        assertEquals("H2", DatabaseBootstrapRunner.normalizeDatabaseLabel("H2"));
    }

    @Test
    @DisplayName("Unknown / blank product name → 'Unknown'; unrecognized name passes through trimmed")
    void fallbacks() {
        assertEquals("Unknown", DatabaseBootstrapRunner.normalizeDatabaseLabel(null));
        assertEquals("Unknown", DatabaseBootstrapRunner.normalizeDatabaseLabel("   "));
        assertEquals("Oracle", DatabaseBootstrapRunner.normalizeDatabaseLabel("  Oracle  "));
    }
}
