package vip.mate.skill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.skill.lifecycle.SkillLifecycleService;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.secret.SkillSecretService;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.skill.workspace.SkillWorkspaceProperties;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for issue #93 — saving a SKILL.md from the admin
 * dialog blew up with "Internal server error".
 *
 * <p>The UI sends a partial PUT body containing only the fields the user
 * edited (e.g. {@code skillContent}, optionally {@code sourceCode}).
 * Two latent problems hit at once:
 * <ol>
 *   <li>Identity fields on the partial entity (notably {@code name})
 *       are {@code null}; the service forwarded the partial entity
 *       straight to {@code syncSkillContentToWorkspace}, which
 *       eventually called {@code String.replaceAll} on the {@code null}
 *       name → NPE.</li>
 *   <li>{@code FieldStrategy.ALWAYS} columns ({@code name_zh},
 *       {@code name_en}, {@code config_json}, {@code manifest_json},
 *       {@code security_scan_result}) were nulled on every save
 *       because MyBatis Plus writes ALWAYS columns even when the
 *       entity field is {@code null}. That's a regression of the
 *       earlier #45 fix, which only patched the resolver write path.</li>
 * </ol>
 *
 * <p>Both have to be fixed by merging the partial update into the
 * existing row server-side before persisting and syncing.
 */
class SkillServiceUpdatePartialTest {

    @Test
    @DisplayName("partial update for a dynamic skill preserves identity and avoids NPE")
    void partialUpdateMergesIntoExisting() throws Exception {
        SkillMapper mapper = mock(SkillMapper.class);
        SkillWorkspaceManager workspaceManager = mock(SkillWorkspaceManager.class);
        SkillWorkspaceProperties workspaceProps = mock(SkillWorkspaceProperties.class);
        SkillSecretService secretService = mock(SkillSecretService.class);
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);

        SkillService service = new SkillService(
                mapper, mock(vip.mate.skill.repository.SkillFileMapper.class),
                workspaceManager, workspaceProps, secretService,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(SkillLifecycleService.class));
        service.setRuntimeService(runtimeService);

        SkillEntity existing = new SkillEntity();
        existing.setId(101L);
        existing.setName("docx");
        existing.setDescription("placeholder");
        existing.setSkillType("dynamic");
        existing.setVersion("1.0.0");
        existing.setEnabled(true);
        existing.setBuiltin(false);
        // Fields that #45 protected — they were already valid pre-update,
        // and must survive an unrelated body edit.
        existing.setNameZh("文档");
        existing.setNameEn("Word docs");
        existing.setConfigJson("{\"foo\":1}");
        existing.setManifestJson("{\"name\":\"docx\"}");
        when(mapper.selectById(101L)).thenReturn(existing);

        // Workspace exists from the create step, so the sync path runs
        // — triggering the NPE on the unpatched code.
        Path tempRoot = Files.createTempDirectory("skill-svc-test");
        Path skillDir = tempRoot.resolve("docx");
        Files.createDirectories(skillDir);
        when(workspaceManager.conventionWorkspaceExists("docx")).thenReturn(true);
        when(workspaceManager.resolveConventionPath("docx")).thenReturn(skillDir);

        // What the controller deserializes from the partial PUT body:
        // only id + skillContent + sourceCode.
        SkillEntity partial = new SkillEntity();
        partial.setId(101L);
        partial.setSkillContent("---\nname: docx\nversion: \"1.1.0\"\n---\n# body\n");
        partial.setSourceCode("");

        assertDoesNotThrow(() -> service.updateSkill(partial),
                "saving a partial body update must not blow up — issue #93");

        // The merged entity that actually hit the DB must keep all the
        // identity / projection fields that were on the row already.
        ArgumentCaptor<SkillEntity> written = ArgumentCaptor.forClass(SkillEntity.class);
        verify(mapper, times(1)).updateById(written.capture());
        SkillEntity persisted = written.getValue();
        assertEquals("docx", persisted.getName(),
                "name must survive a partial body PUT (no FieldStrategy.ALWAYS regression on name)");
        assertEquals("文档", persisted.getNameZh(),
                "name_zh is FieldStrategy.ALWAYS — partial save must not null it out (issue #45 regression)");
        assertEquals("Word docs", persisted.getNameEn(),
                "name_en is FieldStrategy.ALWAYS — partial save must not null it out");
        assertEquals("{\"foo\":1}", persisted.getConfigJson(),
                "config_json is FieldStrategy.ALWAYS — partial save must not null it out");
        assertEquals("{\"name\":\"docx\"}", persisted.getManifestJson(),
                "manifest_json is FieldStrategy.ALWAYS — partial save must not null it out");
        // The user-edited fields actually do get the new values.
        assertNotNull(persisted.getSkillContent());
        org.junit.jupiter.api.Assertions.assertTrue(
                persisted.getSkillContent().contains("version: \"1.1.0\""),
                "skill_content from the partial PUT must be applied");

        // Workspace sync runs — using the merged name, not the partial null.
        verify(workspaceManager).conventionWorkspaceExists("docx");

        // Best-effort cleanup of the temp workspace.
        Files.deleteIfExists(skillDir.resolve("SKILL.md"));
        Files.deleteIfExists(skillDir);
        Files.deleteIfExists(tempRoot);
    }

    @Test
    @DisplayName("partial identity edit (no body) keeps skill_content intact")
    void partialIdentityEditDoesNotClobberBody() {
        SkillMapper mapper = mock(SkillMapper.class);
        SkillWorkspaceManager workspaceManager = mock(SkillWorkspaceManager.class);
        SkillWorkspaceProperties workspaceProps = mock(SkillWorkspaceProperties.class);
        SkillSecretService secretService = mock(SkillSecretService.class);
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);

        SkillService service = new SkillService(
                mapper, mock(vip.mate.skill.repository.SkillFileMapper.class),
                workspaceManager, workspaceProps, secretService,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(SkillLifecycleService.class));
        service.setRuntimeService(runtimeService);

        SkillEntity existing = new SkillEntity();
        existing.setId(202L);
        existing.setName("notes");
        existing.setSkillType("dynamic");
        existing.setBuiltin(false);
        existing.setSkillContent("---\nname: notes\n---\n# previously authored body\n");
        when(mapper.selectById(202L)).thenReturn(existing);
        when(workspaceManager.conventionWorkspaceExists(anyString())).thenReturn(false);

        // Identity edit: nameZh / description only — skill_content is
        // never touched and must survive.
        SkillEntity partial = new SkillEntity();
        partial.setId(202L);
        partial.setNameZh("笔记");
        partial.setDescription("New tag line");

        service.updateSkill(partial);

        ArgumentCaptor<SkillEntity> written = ArgumentCaptor.forClass(SkillEntity.class);
        verify(mapper).updateById(written.capture());
        SkillEntity persisted = written.getValue();
        assertEquals("notes", persisted.getName());
        assertEquals("笔记", persisted.getNameZh());
        assertEquals("New tag line", persisted.getDescription());
        // skill_content was untouched in the PUT body — must keep the old
        // body, not be nulled out by FieldStrategy.ALWAYS on the partial.
        assertNotNull(persisted.getSkillContent(),
                "identity-only PUT must not wipe skill_content");
        org.junit.jupiter.api.Assertions.assertTrue(
                persisted.getSkillContent().contains("previously authored body"));
    }
}
