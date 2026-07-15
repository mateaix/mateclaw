package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.datasource.service.DatasourceConnectionManager;
import vip.mate.datasource.service.DatasourceService;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasourceToolPostgresqlViewDiscoveryTest {

    @Test
    @DisplayName("list_tables discovers PostgreSQL views as queryable relations")
    void listTablesIncludesPostgresqlViews() throws Exception {
        // Given a PostgreSQL datasource whose allowed schema exposes a view.
        DatasourceEntity datasource = new DatasourceEntity();
        datasource.setId(1L);
        datasource.setDbType("postgresql");
        datasource.setSchemaName("serving");

        DatasourceService service = mock(DatasourceService.class);
        DatasourceConnectionManager connectionManager = mock(DatasourceConnectionManager.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(service.getDecrypted(1L)).thenReturn(datasource);
        when(connectionManager.getConnection(datasource)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("table_name");
        when(metadata.getColumnLabel(2)).thenReturn("table_comment");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("v_plan_enriched");
        when(resultSet.getString(2)).thenReturn("read-only serving view");

        DatasourceTool tool = new DatasourceTool(service, connectionManager, JsonMapper.builder().build());

        // When the agent asks MateClaw to discover available relations.
        String output = tool.query_datasource("list_tables", 1L, null);

        // Then the metadata query must include views and return the discovered view.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).executeQuery(sql.capture());
        assertTrue(sql.getValue().contains("information_schema.tables"));
        assertFalse(sql.getValue().contains("FROM pg_tables"));
        assertTrue(output.contains("v_plan_enriched"));
    }
}
