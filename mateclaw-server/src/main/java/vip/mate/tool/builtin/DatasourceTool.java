package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.datasource.service.DatasourceConnectionManager;
import vip.mate.datasource.service.DatasourceService;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 内置工具：数据源发现
 * <p>
 * 提供数据源列表查询、表列表查询、表结构查询三个动作，
 * 供 Agent 在查数场景下发现可用数据源和表结构。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasourceTool {

    private final DatasourceService datasourceService;
    private final DatasourceConnectionManager connectionManager;
    /**
     * The application ObjectMapper, which serializes every {@code Long} as a JSON
     * string (see {@code JacksonConfig}). Tool output goes through it so 19-digit
     * Snowflake ids reach the model as strings — exactly like the HTTP API — and
     * never as JSON numbers that lose their low digits in a double/JS-number
     * round-trip on the way back into a tool call.
     */
    private final ObjectMapper objectMapper;

    /** SQL identifier whitelist: letters, digits, underscore, dot, hyphen only */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_][a-zA-Z0-9_.\\-]{0,127}$");

    private static String sanitizeIdentifier(String name) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
        return name;
    }

    @Tool(description = """
            查询外部数据源的元数据。支持三种动作：
            1. action='list_datasources' — 列出所有可用数据源（无需其他参数）
            2. action='list_tables' — 列出指定数据源中的所有表（需要 datasourceId）
            3. action='describe_table' — 查看指定表的列详情（需要 datasourceId 和 tableName）
            """)
    public String query_datasource(
            @ToolParam(description = "动作：list_datasources / list_tables / describe_table") String action,
            @ToolParam(description = "数据源 ID（list_tables 和 describe_table 时必填）", required = false) Long datasourceId,
            @ToolParam(description = "表名（describe_table 时必填）", required = false) String tableName) {

        try {
            return switch (action) {
                case "list_datasources" -> listDatasources();
                case "list_tables" -> listTables(datasourceId);
                case "describe_table" -> describeTable(datasourceId, tableName);
                default -> error("未知动作: " + action + "，支持: list_datasources / list_tables / describe_table");
            };
        } catch (Exception e) {
            log.error("数据源查询失败: {}", e.getMessage(), e);
            return error(e.getMessage());
        }
    }

    private String listDatasources() throws Exception {
        List<DatasourceEntity> list = datasourceService.listEnabled();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DatasourceEntity ds : list) {
            Map<String, Object> obj = new LinkedHashMap<>();
            // ds.getId() is a Long; the shared ObjectMapper renders it as a JSON
            // string so the model copies an exact id back into list_tables /
            // execute_sql / describe_table calls.
            obj.put("id", ds.getId());
            obj.put("name", ds.getName());
            obj.put("dbType", ds.getDbType());
            obj.put("databaseName", ds.getDatabaseName());
            obj.put("description", ds.getDescription());
            rows.add(obj);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasources", rows);
        result.put("count", rows.size());
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    }

    private String listTables(Long datasourceId) throws SQLException {
        if (datasourceId == null) {
            return error("list_tables 需要 datasourceId 参数");
        }
        DatasourceEntity entity = datasourceService.getDecrypted(datasourceId);
        String dbType = entity.getDbType().toLowerCase();

        String sql = switch (dbType) {
            case "mysql", "mariadb" -> String.format(
                    "SELECT TABLE_NAME, TABLE_COMMENT, TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = '%s' ORDER BY TABLE_NAME",
                    sanitizeIdentifier(entity.getDatabaseName()));
            case "postgresql" -> String.format(
                    "SELECT tablename AS table_name, obj_description(c.oid) AS table_comment " +
                    "FROM pg_tables t LEFT JOIN pg_class c ON c.relname = t.tablename " +
                    "WHERE t.schemaname = '%s' ORDER BY tablename",
                    sanitizeIdentifier(entity.getSchemaName() != null ? entity.getSchemaName() : "public"));
            case "clickhouse" -> "SHOW TABLES";
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };

        try (Connection conn = connectionManager.getConnection(entity);
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(15);
            ResultSet rs = stmt.executeQuery(sql);
            return formatResultSet(rs, 200);
        }
    }

    private String describeTable(Long datasourceId, String tableName) throws SQLException {
        if (datasourceId == null || tableName == null || tableName.isBlank()) {
            return error("describe_table 需要 datasourceId 和 tableName 参数");
        }
        DatasourceEntity entity = datasourceService.getDecrypted(datasourceId);
        String dbType = entity.getDbType().toLowerCase();

        String sql = switch (dbType) {
            case "mysql", "mariadb" -> String.format(
                    "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT " +
                    "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' ORDER BY ORDINAL_POSITION",
                    sanitizeIdentifier(entity.getDatabaseName()), sanitizeIdentifier(tableName));
            case "postgresql" -> String.format(
                    "SELECT c.column_name, c.data_type, c.is_nullable, " +
                    "CASE WHEN pk.column_name IS NOT NULL THEN 'PRI' ELSE '' END AS column_key, " +
                    "c.column_default, pgd.description AS column_comment " +
                    "FROM information_schema.columns c " +
                    "LEFT JOIN (SELECT ku.column_name FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage ku ON tc.constraint_name = ku.constraint_name " +
                    "WHERE tc.table_name = '%s' AND tc.constraint_type = 'PRIMARY KEY') pk ON c.column_name = pk.column_name " +
                    "LEFT JOIN pg_catalog.pg_statio_all_tables st ON st.relname = c.table_name " +
                    "LEFT JOIN pg_catalog.pg_description pgd ON pgd.objoid = st.relid AND pgd.objsubid = c.ordinal_position " +
                    "WHERE c.table_schema = '%s' AND c.table_name = '%s' ORDER BY c.ordinal_position",
                    sanitizeIdentifier(tableName),
                    sanitizeIdentifier(entity.getSchemaName() != null ? entity.getSchemaName() : "public"),
                    sanitizeIdentifier(tableName));
            case "clickhouse" -> String.format("DESCRIBE TABLE %s", sanitizeIdentifier(tableName));
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };

        try (Connection conn = connectionManager.getConnection(entity);
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(15);
            ResultSet rs = stmt.executeQuery(sql);
            return formatResultSet(rs, 500);
        }
    }

    /**
     * 将 ResultSet 格式化为 Markdown 表格
     */
    private String formatResultSet(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        // 表头
        StringBuilder sb = new StringBuilder();
        List<String> headers = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            headers.add(meta.getColumnLabel(i));
        }
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("| ").append("--- | ".repeat(colCount)).append("\n");

        // 数据行
        int rowCount = 0;
        while (rs.next() && rowCount < maxRows) {
            sb.append("| ");
            for (int i = 1; i <= colCount; i++) {
                String val = rs.getString(i);
                sb.append(val != null ? val.replace("|", "\\|") : "NULL");
                if (i < colCount) sb.append(" | ");
            }
            sb.append(" |\n");
            rowCount++;
        }

        if (rowCount == 0) {
            return "查询结果为空";
        }
        sb.append("\n共 ").append(rowCount).append(" 条记录");
        if (rowCount >= maxRows) {
            sb.append("（已截断，实际可能更多）");
        }
        return sb.toString();
    }

    private String error(String message) {
        return JSONUtil.toJsonStr(new JSONObject().set("error", message));
    }
}
