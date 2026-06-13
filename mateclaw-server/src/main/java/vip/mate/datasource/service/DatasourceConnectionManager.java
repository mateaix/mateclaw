package vip.mate.datasource.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.exception.MateClawException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部数据源连接池管理器
 * <p>
 * 每个数据源维护一个独立的 HikariCP 连接池（max=3），
 * 通过 ConcurrentHashMap 缓存，配置变更时自动失效。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class DatasourceConnectionManager implements DisposableBean {

    private final ConcurrentHashMap<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    /**
     * 获取指定数据源的 JDBC 连接
     */
    public Connection getConnection(DatasourceEntity entity) {
        HikariDataSource ds = pools.computeIfAbsent(entity.getId(), id -> createPool(entity));
        try {
            return ds.getConnection();
        } catch (SQLException e) {
            throw new MateClawException("err.datasource.connection_failed", "获取数据库连接失败: " + e.getMessage());
        }
    }

    /**
     * 失效指定数据源的连接池（配置更新/删除时调用）
     */
    public void invalidate(Long datasourceId) {
        HikariDataSource ds = pools.remove(datasourceId);
        if (ds != null && !ds.isClosed()) {
            ds.close();
            log.info("已关闭数据源连接池: {}", datasourceId);
        }
    }

    /**
     * 创建临时连接测试数据源连通性
     */
    public boolean testConnection(DatasourceEntity entity) {
        HikariConfig config = buildConfig(entity);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        try (HikariDataSource testDs = new HikariDataSource(config);
             Connection conn = testDs.getConnection()) {
            return conn.isValid(5);
        } catch (Exception e) {
            log.warn("数据源连接测试失败 [{}]: {}", entity.getName(), e.getMessage());
            return false;
        }
    }

    private HikariDataSource createPool(DatasourceEntity entity) {
        HikariConfig config = buildConfig(entity);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setPoolName("mateclaw-ds-" + entity.getId());
        log.info("创建数据源连接池: {} ({})", entity.getName(), entity.getDbType());
        return new HikariDataSource(config);
    }

    private HikariConfig buildConfig(DatasourceEntity entity) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildJdbcUrl(entity));
        if (entity.getUsername() != null) {
            config.setUsername(entity.getUsername());
        }
        if (entity.getPassword() != null) {
            config.setPassword(entity.getPassword());
        }
        // 安全：设置连接为只读模式
        config.setReadOnly(true);
        return config;
    }

    /**
     * 根据数据库类型构建 JDBC URL
     */
    public static String buildJdbcUrl(DatasourceEntity entity) {
        String dbType = entity.getDbType().toLowerCase();
        String host = entity.getHost();
        int port = entity.getPort();
        String dbName = entity.getDatabaseName();
        String extra = entity.getExtraParams();

        String baseUrl;
        switch (dbType) {
            case "mysql":
            case "mariadb":
                baseUrl = String.format("jdbc:mysql://%s:%d/%s", host, port, dbName);
                if (extra == null || extra.isBlank()) {
                    extra = "useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true";
                }
                break;
            case "postgresql":
                baseUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName);
                if (entity.getSchemaName() != null && !entity.getSchemaName().isBlank()) {
                    String schemaParam = "currentSchema=" + entity.getSchemaName();
                    extra = (extra == null || extra.isBlank()) ? schemaParam : extra + "&" + schemaParam;
                }
                break;
            case "kingbase":
            case "kingbasees":
                baseUrl = String.format("jdbc:kingbase8://%s:%d/%s", host, port, dbName);
                if (entity.getSchemaName() != null && !entity.getSchemaName().isBlank()) {
                    String schemaParam = "currentSchema=" + entity.getSchemaName();
                    extra = (extra == null || extra.isBlank()) ? schemaParam : extra + "&" + schemaParam;
                }
                break;
            case "clickhouse":
                baseUrl = String.format("jdbc:clickhouse://%s:%d/%s", host, port, dbName);
                break;
            default:
                throw new MateClawException("err.datasource.unsupported_db", "不支持的数据库类型: " + dbType);
        }

        if (extra != null && !extra.isBlank()) {
            // 安全检查：拒绝危险参数
            String lowerExtra = extra.toLowerCase();
            if (lowerExtra.contains("allowloadlocalinfile") || lowerExtra.contains("autodeserialize")) {
                throw new MateClawException("err.datasource.unsafe_jdbc", "JDBC 参数包含不安全选项");
            }
            baseUrl += (baseUrl.contains("?") ? "&" : "?") + extra;
        }
        return baseUrl;
    }

    @Override
    public void destroy() {
        log.info("关闭所有外部数据源连接池 (共 {} 个)", pools.size());
        pools.forEach((id, ds) -> {
            if (!ds.isClosed()) {
                ds.close();
            }
        });
        pools.clear();
    }
}
