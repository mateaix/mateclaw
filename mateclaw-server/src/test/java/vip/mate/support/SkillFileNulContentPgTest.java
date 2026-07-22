package vip.mate.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vip.mate.skill.model.SkillFileEntity;
import vip.mate.skill.service.SkillFileService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the PostgreSQL text contract for skill bundle files against the NUL
 * byte ({@code  }).
 *
 * <p>PostgreSQL rejects {@code 0x00} in a {@code text} column ("invalid byte
 * sequence for encoding UTF8: 0x00"), whereas MySQL ({@code utf8mb4 TEXT}) and
 * H2 ({@code CLOB}) silently accept it — so this failure mode is invisible on
 * the default dev database and only a real PostgreSQL server can prove the fix.
 * {@code SkillFileService.applyBundleFiles} is the single write chokepoint and
 * strips NUL before persist; without that strip the insert below would throw on
 * PostgreSQL.
 */
@DisplayName("Skill file content with a NUL byte persists on PostgreSQL")
class SkillFileNulContentPgTest extends PostgresE2EBaseTest {

    @Autowired
    private SkillFileService skillFileService;

    @Test
    @DisplayName("applyBundleFiles strips NUL so the TEXT insert succeeds and reads back clean")
    void nulIsStrippedBeforePersist() {
        long skillId = 990101L;
        // An otherwise-text file carrying a stray NUL — the case that slips past
        // a prefix-only binary check and dies on PostgreSQL's TEXT column.
        String dirty = "print('hello')\u0000\nprint('world')";

        SkillFileService.ApplyResult result = skillFileService.applyBundleFiles(
                skillId,
                Map.of("scripts/run.py", dirty),
                false);

        assertThat(result.rowsWritten()).isEqualTo(1);

        List<SkillFileEntity> rows = skillFileService.listBySkillId(skillId);
        assertThat(rows).hasSize(1);
        SkillFileEntity row = rows.get(0);
        assertThat(row.getFilePath()).isEqualTo("scripts/run.py");
        // The persisted content is the NUL-free form; the rest is preserved.
        assertThat(row.getContent()).isEqualTo("print('hello')\nprint('world')");
        assertThat(row.getContent()).doesNotContain("\u0000");
        // content_size reflects the stored (stripped) content, not the original.
        assertThat(row.getContentSize())
                .isEqualTo("print('hello')\nprint('world')".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
}
