package vip.mate.tool.mcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.repository.McpServerMapper;
import vip.mate.tool.mcp.runtime.McpClientManager;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpServerWorkspaceVisibilityTest {
    McpServerEntity server(Long workspace) {
        McpServerEntity entity = new McpServerEntity();
        entity.setWorkspaceId(workspace);
        return entity;
    }

    @Test void explicitWorkspaceSeesOwnAndGlobalWhileMissingContextSeesGlobalOnly() {
        var global = server(null); var own = server(1L); var foreign = server(2L);
        McpServerMapper mapper = mock(McpServerMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(global, own, foreign));
        McpServerService service = new McpServerService(mapper, mock(McpClientManager.class), mock(ApplicationEventPublisher.class));
        try {
            assertEquals(List.of(global, own), service.listVisible(1L));
            assertEquals(List.of(global, foreign), service.listEnabledVisible(2L));
            assertEquals(List.of(global), service.listVisible(null));
            assertEquals(List.of(global, own, foreign), service.listAll());
            assertEquals(1L, service.sanitize(own).getWorkspaceId());
        } finally { service.shutdownConnectExecutor(); }
    }

    @Test void migrationPreservesHistoricalServersAsGlobal() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:mcp_ownership;MODE=MySQL")) {
            var statement = connection.createStatement();
            statement.execute("CREATE TABLE mate_mcp_server (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("INSERT INTO mate_mcp_server VALUES (7, 'legacy')");
            String sql = Files.readString(Path.of("src/main/resources/db/migration/h2/V204__mcp_workspace_ownership.sql"));
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var result = statement.executeQuery("SELECT name, workspace_id FROM mate_mcp_server WHERE id=7");
            assertTrue(result.next()); assertEquals("legacy", result.getString(1)); assertNull(result.getObject(2));
        }
    }
}
