package vip.mate.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strict migration startup by default. Repair is an explicit operator opt-in:
 * changing applied checksums must never silently rewrite migration history.
 */
@Slf4j
@Configuration
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationInitializer flywayInitializer(Flyway flyway,
            @Value("${mateclaw.flyway.auto-repair:false}") boolean autoRepair) {
        return new FlywayMigrationInitializer(flyway, f -> {
            if (autoRepair) {
                log.warn("[Flyway] Explicit auto-repair is enabled; applied migration history may be changed");
                f.repair();
            }
            // Let Flyway validate before migration. In particular, checksum drift
            // must fail startup rather than be hidden by an automatic repair.
            f.migrate();
        });
    }
}
