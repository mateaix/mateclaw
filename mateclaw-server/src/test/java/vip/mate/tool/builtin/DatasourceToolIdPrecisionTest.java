package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.datasource.service.DatasourceConnectionManager;
import vip.mate.datasource.service.DatasourceService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #319: a 19-digit Snowflake datasource id must reach the model as a JSON
 * string, not a number. As a number it loses its low digits when it round-trips
 * through a double / JS Number on the way back into a follow-up tool call (and in
 * the chat UI), so the looked-up datasource is "not found". The tool serializes
 * through the application ObjectMapper, which renders every Long as a string —
 * the same id-safety policy the HTTP API already applies.
 */
class DatasourceToolIdPrecisionTest {

    /** Mirrors the application ObjectMapper: every Long serializes as a string. */
    private static ObjectMapper idSafeMapper() {
        SimpleModule m = new SimpleModule();
        m.addSerializer(Long.class, ToStringSerializer.instance);
        m.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return JsonMapper.builder().addModule(m).build();
    }

    @Test
    @DisplayName("list_datasources emits the id as a quoted JSON string, never a bare number")
    void listDatasources_idIsString() {
        long bigId = 2064875200729235458L;
        DatasourceEntity ds = new DatasourceEntity();
        ds.setId(bigId);
        ds.setName("prod-mysql");
        ds.setDbType("mysql");
        ds.setDatabaseName("app");

        DatasourceService service = mock(DatasourceService.class);
        when(service.listEnabled()).thenReturn(List.of(ds));
        DatasourceTool tool = new DatasourceTool(service, mock(DatasourceConnectionManager.class), idSafeMapper());

        String out = tool.query_datasource("list_datasources", null, null);

        assertTrue(out.contains("\"" + bigId + "\""),
                "id must appear as a quoted string so its 19 digits survive the round-trip; got: " + out);
        assertFalse(out.contains(": " + bigId) || out.contains(":" + bigId),
                "id must NOT appear as a bare JSON number (precision-lossy across double/JS Number)");
    }
}
