package vip.mate;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * MateClaw - Personal AI Assistant
 * Powered by Spring AI Alibaba
 *
 * @author MateClaw Team
 */
@Slf4j
@SpringBootApplication(exclude = {
    // Disable Spring AI MCP Client auto-configuration (lifecycle owned by McpClientManager).
    org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration.class,
    org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration.class,
    org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration.class,
    org.springframework.ai.mcp.client.common.autoconfigure.annotations.McpClientAnnotationScannerAutoConfiguration.class,
    org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration.class,
    org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration.class,
    // DashScopeAgent is the Bailian "Application Agent" (Bailian-hosted prompt+tool app),
    // not the chat model. We don't use it — model configuration is admin-UI driven and
    // built by DashScopeChatModelBuilder. Its auto-config strictly requires
    // spring.ai.dashscope.api-key to be non-empty at startup, which makes the whole
    // ApplicationContext fail when users deploy via Docker without setting the key.
    com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration.class,
})
@EnableScheduling
@MapperScan("vip.mate.**.repository")
public class MateClawApplication {

    @Autowired
    private DataSource dataSource;

    /** Cached DbType for the PaginationInnerInterceptor. */
    private volatile DbType resolvedDbType;

    public static void main(String[] args) {
        SpringApplication.run(MateClawApplication.class, args);
    }

    /**
     * Detect the actual database type from the live DataSource so the
     * {@link PaginationInnerInterceptor} always uses the correct dialect,
     * even when the JDBC URL is wrapped by a proxy (HikariCP, P6Spy, etc.).
     *
     * <p>DbType is cached after the first successful detection; a failure
     * falls back to the value set in {@code mybatis-plus.global-config.db-config.db-type},
     * or eventually to {@link DbType#MYSQL} — but by then the connection
     * pool would already have failed.
     */
    @PostConstruct
    void detectDbType() {
        try (Connection conn = dataSource.getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            if (productName.contains("kingbase")) {
                resolvedDbType = DbType.KINGBASE_ES;
            } else if (productName.contains("postgresql")) {
                resolvedDbType = DbType.POSTGRE_SQL;
            } else if (productName.contains("mysql") || productName.contains("mariadb")) {
                resolvedDbType = DbType.MYSQL;
            } else if (productName.contains("h2")) {
                resolvedDbType = DbType.H2;
            } else {
                // Let the PaginationInnerInterceptor auto-detect at query time
                resolvedDbType = null;
            }
            if (resolvedDbType != null) {
                log.info("Detected database type: {} (product={})", resolvedDbType, productName);
            }
        } catch (Exception e) {
            log.warn("Could not detect database type — PaginationInnerInterceptor will auto-detect on first query: {}",
                    e.getMessage());
        }
    }

    /**
     * MyBatis Plus pagination plugin.
     *
     * <p>When {@code resolvedDbType} is available the interceptor uses it directly;
     * otherwise it falls back to JDBC-URL auto-detection, which works for
     * {@code jdbc:kingbase8://} but not for proxied DataSources (RFC-042 P0).
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = resolvedDbType != null
                ? new PaginationInnerInterceptor(resolvedDbType)
                : new PaginationInnerInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * Print a clear "READY" banner after all post-startup initialization,
     * so operators can tell at a glance when the application is ready to serve.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════════╗");
        log.info("║  MateClaw is READY  ✓                                              ║");
        log.info("║  Web UI →  http://localhost:18088                                  ║");
        log.info("║  Swagger → http://localhost:18088/swagger-ui.html                  ║");
        log.info("╚══════════════════════════════════════════════════════════════════════╝");
        log.info("");
    }
}
