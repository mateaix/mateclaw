package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.cron.model.CronJobDTO;
import vip.mate.cron.service.CronJobService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #319 (same class as the datasource fix): a 19-digit Snowflake {@code jobId}
 * must reach the model as a JSON string, not a number — otherwise it loses its low
 * digits when the model copies it back into toggle_cron_job / delete_cron_job and
 * the wrong (or no) job is hit.
 */
class CronJobToolIdPrecisionTest {

    private static ObjectMapper idSafeMapper() {
        SimpleModule m = new SimpleModule();
        m.addSerializer(Long.class, ToStringSerializer.instance);
        m.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return JsonMapper.builder().addModule(m).build();
    }

    @Test
    @DisplayName("list_cron_jobs emits jobId as a quoted JSON string, never a bare number")
    void listCronJobs_jobIdIsString() {
        long bigId = 2064875200729235458L;
        CronJobDTO job = new CronJobDTO();
        job.setId(bigId);
        job.setName("Daily summary");
        job.setEnabled(true);

        CronJobService service = mock(CronJobService.class);
        when(service.list(any())).thenReturn(List.of(job));
        CronJobTool tool = new CronJobTool(service, idSafeMapper());

        String out = tool.list_cron_jobs(null);

        assertTrue(out.contains("\"" + bigId + "\""),
                "jobId must appear as a quoted string so its 19 digits survive; got: " + out);
        assertFalse(out.contains(": " + bigId) || out.contains(":" + bigId),
                "jobId must NOT appear as a bare JSON number");
    }

    @Test
    @DisplayName("cron mutating tools publish jobId as a string parameter so LLM tool calls preserve precision")
    void cronJobIdSchemasAreString() throws Exception {
        CronJobTool tool = new CronJobTool(mock(CronJobService.class), idSafeMapper());

        assertJobIdIsString(tool, "toggle_cron_job");
        assertJobIdIsString(tool, "delete_cron_job");
    }

    private static void assertJobIdIsString(Object tool, String name) throws Exception {
        JsonNode root = idSafeMapper().readTree(callback(tool, name).getToolDefinition().inputSchema());

        assertTrue("string".equals(root.at("/properties/jobId/type").asText()),
                name + " jobId must be a string schema");
    }

    private static ToolCallback callback(Object tool, String name) {
        for (ToolCallback callback : ToolCallbacks.from(tool)) {
            if (name.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        throw new AssertionError("Missing tool callback: " + name);
    }
}
