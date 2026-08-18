package vip.mate.tool.builtin;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.datasource.service.DatasourceConnectionManager;
import vip.mate.datasource.service.DatasourceService;
import vip.mate.datasource.service.EChartsOptionBuilder;
import vip.mate.datasource.service.SqlValidationService;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置工具：SQL 查询执行
 * <p>
 * 仅允许 SELECT 语句。自动注入 LIMIT 保护。
 * 查询超时 30 秒。结果格式化为 Markdown 表格或 JSON。
 * 自动分析数据特征并生成 ECharts 图表配置。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlQueryTool {

    private final DatasourceService datasourceService;
    private final DatasourceConnectionManager connectionManager;
    private final SqlValidationService sqlValidationService;

    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private static final int MAX_ROWS = 500;
    private static final int MARKDOWN_TABLE_THRESHOLD = 20;

    @Tool(description = """
            在外部数据源上执行只读 SQL 查询。
            仅允许 SELECT 语句，禁止 INSERT/UPDATE/DELETE/DROP 等写操作。
            如果 SQL 没有 LIMIT 子句会自动添加 LIMIT 500。
            返回查询结果（Markdown 表格或 JSON 格式）以及行数和执行耗时。
            如果数据适合可视化，会自动附带一个 echarts 图表配置代码块，前端会自动渲染为交互式图表。
            """)
    public String execute_sql(
            @ToolParam(description = "目标数据源 ID。必须作为字符串传入，避免大整数精度丢失") String datasourceId,
            @ToolParam(description = "要执行的 SQL 查询（仅允许 SELECT）") String sql) {

        try {
            Long parsedDatasourceId = parseDatasourceId(datasourceId);
            // 1. 验证并规范化 SQL
            String safeSql = sqlValidationService.validateAndNormalize(sql);
            log.info("执行 SQL 查询 [数据源 {}]: {}", parsedDatasourceId, safeSql);

            // 2. 获取数据源连接
            DatasourceEntity entity = datasourceService.getDecrypted(parsedDatasourceId);

            // 3. 执行查询
            long startTime = System.currentTimeMillis();
            try (Connection conn = connectionManager.getConnection(entity);
                 Statement stmt = conn.createStatement()) {

                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setMaxRows(MAX_ROWS);
                ResultSet rs = stmt.executeQuery(safeSql);

                long elapsed = System.currentTimeMillis() - startTime;
                return formatResult(rs, safeSql, elapsed);
            }
        } catch (Exception e) {
            log.error("SQL 查询执行失败: {}", e.getMessage(), e);
            JSONObject result = new JSONObject();
            result.set("error", e.getMessage());
            result.set("sql", sql);
            return result.toStringPretty();
        }
    }

    private Long parseDatasourceId(String datasourceId) {
        String trimmed = datasourceId != null ? datasourceId.trim() : "";
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("datasourceId is required");
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("datasourceId must be a numeric string");
        }
    }

    private String formatResult(ResultSet rs, String sql, long elapsedMs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        // 收集列名
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        // 收集数据
        List<List<String>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < MAX_ROWS) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                String val = rs.getString(i);
                row.add(val != null ? val : "NULL");
            }
            rows.add(row);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**SQL**: `").append(sql).append("`\n");
        sb.append("**结果**: ").append(rows.size()).append(" 行, ").append(colCount).append(" 列");
        sb.append(" (耗时 ").append(elapsedMs).append("ms)\n\n");

        if (rows.isEmpty()) {
            sb.append("查询结果为空。");
            return sb.toString();
        }

        if (rows.size() <= MARKDOWN_TABLE_THRESHOLD && colCount <= 10) {
            // Markdown 表格格式
            sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
            sb.append("| ").append("--- | ".repeat(colCount)).append("\n");
            for (List<String> row : rows) {
                sb.append("| ");
                for (int i = 0; i < row.size(); i++) {
                    sb.append(row.get(i).replace("|", "\\|"));
                    if (i < row.size() - 1) sb.append(" | ");
                }
                sb.append(" |\n");
            }
        } else {
            // JSON 格式（大结果集）
            JSONArray jsonRows = new JSONArray();
            for (List<String> row : rows) {
                JSONObject obj = new JSONObject();
                for (int i = 0; i < columns.size(); i++) {
                    obj.set(columns.get(i), row.get(i));
                }
                jsonRows.add(obj);
            }
            sb.append(jsonRows.toStringPretty());
        }

        if (rows.size() >= MAX_ROWS) {
            sb.append("\n\n> 结果已截断至 ").append(MAX_ROWS).append(" 行，实际数据可能更多。");
        }

        // 自动生成 ECharts 图表配置
        String chartOption = EChartsOptionBuilder.tryBuild(columns, rows);
        if (chartOption != null) {
            sb.append("\n\n```echarts\n").append(chartOption).append("\n```");
        }

        return sb.toString();
    }
}
