package vip.mate.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.model.DeliveryConfig;
import vip.mate.cron.repository.CronJobMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the JSONB write/read path end-to-end on a real PostgreSQL server,
 * specifically through MyBatis-Plus's {@link
 * com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler} on
 * {@code CronJobEntity.deliveryConfig} (the only typeHandler-mapped JSON column
 * in the codebase).
 *
 * <p>The handler binds the serialized JSON via {@code setString}, which would
 * fail against a {@code jsonb} column ("column is of type jsonb but expression
 * is of type character varying") without {@code stringtype=unspecified} on the
 * JDBC URL. This test is the guard that the production URL setting actually
 * makes that path work.
 */
@DisplayName("CronJob.deliveryConfig round-trips through a JSONB column")
class CronJobDeliveryConfigPgTest extends PostgresE2EBaseTest {

    @Autowired
    private CronJobMapper cronJobMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("delivery_config is JSONB and JacksonTypeHandler round-trips")
    void deliveryConfigRoundTripsThroughJsonb() {
        long id = 990001L;
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setWorkspaceId(1L);
        job.setName("pg-jsonb-roundtrip");
        job.setCronExpression("0 9 * * *");
        job.setTimezone("Asia/Shanghai");
        job.setAgentId(1L);
        job.setTaskType("reminder");
        job.setEnabled(true);
        job.setDeleted(0);
        job.setCreateTime(LocalDateTime.now());
        job.setUpdateTime(LocalDateTime.now());
        // 3-arg legacy constructor: targetId / threadId / accountId.
        job.setDeliveryConfig(new DeliveryConfig("u-123", "thread-7", null));

        assertThat(cronJobMapper.insert(job)).isEqualTo(1);

        // The column is physically JSONB.
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'mateclaw' AND table_name = 'mate_cron_job' "
                        + "AND column_name = 'delivery_config'",
                String.class);
        assertThat(dataType).isEqualTo("jsonb");

        // JacksonTypeHandler deserializes it back to the record.
        CronJobEntity back = cronJobMapper.selectById(id);
        assertThat(back).isNotNull();
        assertThat(back.getDeliveryConfig()).isNotNull();
        assertThat(back.getDeliveryConfig().targetId()).isEqualTo("u-123");
        assertThat(back.getDeliveryConfig().threadId()).isEqualTo("thread-7");

        // JSONB is queryable by key on the server side (proves it's real JSON,
        // not an opaque string blob).
        String targetViaSql = jdbcTemplate.queryForObject(
                "SELECT delivery_config ->> 'targetId' FROM mateclaw.mate_cron_job WHERE id = ?",
                String.class, id);
        assertThat(targetViaSql).isEqualTo("u-123");
    }

    @Test
    @DisplayName("a JSONB column rejects malformed JSON")
    void jsonbRejectsInvalidJson() {
        // Direct insert of a non-JSON literal into a jsonb column must fail —
        // this is the data-integrity payoff of the TEXT->JSONB upgrade.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO mateclaw.mate_channel "
                        + "(id, name, channel_type, config_json, enabled, workspace_id, create_time, update_time) "
                        + "VALUES (?, 'bad', 'feishu', 'not-valid-json', FALSE, 1, NOW(), NOW())",
                990002L))
                .isInstanceOf(Exception.class);
    }
}
