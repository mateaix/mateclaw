package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.datasource.service.DatasourceConnectionManager;
import vip.mate.datasource.service.DatasourceService;
import vip.mate.datasource.service.SqlValidationService;
import vip.mate.tool.mcp.runtime.McpToolResultCapture;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLTimeoutException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SQL and datasource tools return their error text to the model, but must also
 * mark the call failed so execution receipts never count a timeout as success.
 */
class SqlToolFailureReportingTest {

    private final DatasourceService datasourceService = mock(DatasourceService.class);
    private final DatasourceConnectionManager connectionManager = mock(DatasourceConnectionManager.class);
    private final SqlValidationService validation = mock(SqlValidationService.class);
    private final DatasourceEntity datasource = new DatasourceEntity();
    private final McpToolResultCapture capture = new McpToolResultCapture();
    private final ToolContext context = capture.attach(new ToolContext(Map.of()));

    @Test
    void queryTimeoutIsRecordedAsFailedCall() {
        when(validation.validateAndNormalize("SELECT COUNT(*) FROM orders")).thenReturn("SELECT COUNT(*) FROM orders LIMIT 500");
        when(datasourceService.getDecrypted(1L)).thenReturn(datasource);
        when(connectionManager.getConnection(datasource)).thenAnswer(call -> {
            throw new SQLTimeoutException("Statement cancelled due to timeout");
        });
        SqlQueryTool tool = new SqlQueryTool(datasourceService, connectionManager, validation);

        String output = tool.execute_sql("1", "SELECT COUNT(*) FROM orders", context);

        assertTrue(output.contains("Statement cancelled due to timeout"));
        assertTrue(capture.isError());
        assertTrue(capture.errorText().contains("timeout"));
    }

    @Test
    void rejectedStatementIsRecordedAsFailedCall() {
        when(validation.validateAndNormalize("DELETE FROM orders")).thenThrow(new IllegalArgumentException("Only SELECT is allowed"));
        SqlQueryTool tool = new SqlQueryTool(datasourceService, connectionManager, validation);

        tool.execute_sql("1", "DELETE FROM orders", context);

        assertTrue(capture.isError());
    }

    @Test
    void successfulQueryIsNotMarkedFailed() throws Exception {
        try (Connection setup = DriverManager.getConnection("jdbc:h2:mem:sql_tool_ok;DB_CLOSE_DELAY=-1")) {
            setup.createStatement().execute("CREATE TABLE orders(id INT, amount INT)");
            setup.createStatement().execute("INSERT INTO orders VALUES (1, 42)");
            when(validation.validateAndNormalize("SELECT SUM(amount) AS total FROM orders"))
                    .thenReturn("SELECT SUM(amount) AS total FROM orders LIMIT 500");
            when(datasourceService.getDecrypted(1L)).thenReturn(datasource);
            when(connectionManager.getConnection(datasource))
                    .thenAnswer(call -> DriverManager.getConnection("jdbc:h2:mem:sql_tool_ok"));
            SqlQueryTool tool = new SqlQueryTool(datasourceService, connectionManager, validation);

            String output = tool.execute_sql("1", "SELECT SUM(amount) AS total FROM orders", context);

            assertTrue(output.contains("42"));
            assertFalse(capture.isError());
        }
    }

    @Test
    void datasourceMetadataFailureIsRecordedAsFailedCall() {
        when(datasourceService.getDecrypted(1L)).thenThrow(new IllegalStateException("datasource not found"));
        DatasourceTool tool = new DatasourceTool(datasourceService, connectionManager, JsonMapper.builder().build());

        String output = tool.query_datasource("list_tables", "1", null, context);

        assertTrue(output.contains("datasource not found"));
        assertTrue(capture.isError());
    }

    @Test
    void unknownDatasourceActionIsRecordedAsFailedCall() {
        DatasourceTool tool = new DatasourceTool(datasourceService, connectionManager, JsonMapper.builder().build());

        tool.query_datasource("drop_table", "1", null, context);

        assertTrue(capture.isError());
    }

    @Test
    void missingContextStillReturnsErrorText() {
        when(datasourceService.getDecrypted(1L)).thenThrow(new IllegalStateException("datasource not found"));
        DatasourceTool tool = new DatasourceTool(datasourceService, connectionManager, JsonMapper.builder().build());

        assertTrue(tool.query_datasource("list_tables", "1", null, null).contains("datasource not found"));
    }
}
