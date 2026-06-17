package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
