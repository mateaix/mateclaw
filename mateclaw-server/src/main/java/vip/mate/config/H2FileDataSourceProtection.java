package vip.mate.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/** Prevent a cancelled tool's file I/O from closing the shared embedded H2 database. */
@Slf4j
@Component
public class H2FileDataSourceProtection implements BeanPostProcessor {
    private static final String H2_PREFIX = "jdbc:h2:";

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource source) {
            String url = source.getJdbcUrl();
            if (url != null && url.startsWith(H2_PREFIX)) {
                String path = url.substring(H2_PREFIX.length());
                if (path.startsWith("file:") || path.startsWith("nio:")
                        || path.startsWith("./") || path.startsWith("../")
                        || path.startsWith("/") || path.startsWith("~/")
                        || path.matches("^[A-Za-z]:[\\\\/].*")) {
                    // H2's retry filesystem reopens channels closed by interruption.
                    // Preserve the exact underlying path/options, including externally
                    // configured and desktop data directories. No data migration occurs.
                    source.setJdbcUrl(H2_PREFIX + "retry:" + path);
                    log.info("[H2] Enabled interrupt-resilient embedded file access");
                }
            }
        }
        return bean;
    }
}
