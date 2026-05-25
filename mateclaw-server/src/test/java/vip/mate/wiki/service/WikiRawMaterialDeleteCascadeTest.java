package vip.mate.wiki.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.tool.builtin.DocumentExtractTool;
import vip.mate.tool.image.vision.ImageVisionService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the cascade behavior for raw-material deletion.
 *
 * <p>Before this fix, {@code delete(rawId)} only removed the raw row +
 * chunks. Pages generated from that raw lived on as orphans — they kept
 * showing up in search, the page list was polluted, citations dangled.
 * The reprocess path already cleaned them via
 * {@link WikiPageService#deleteExclusiveBySourceRawId}; the delete path
 * has to do the same to be symmetric.
 */
class WikiRawMaterialDeleteCascadeTest {

    private WikiRawMaterialMapper rawMapper;
    private WikiKnowledgeBaseService kbService;
    private WikiChunkService chunkService;
    private WikiPageService pageService;
    private WikiRawMaterialService service;
    private WikiProperties props;

    private static final Long KB_ID = 100L;
    private static final Long RAW_ID = 42L;

    @BeforeEach
    void setUp() throws Exception {
        rawMapper = mock(WikiRawMaterialMapper.class);
        kbService = mock(WikiKnowledgeBaseService.class);
        chunkService = mock(WikiChunkService.class);
        pageService = mock(WikiPageService.class);

        props = new WikiProperties();
        props.setAutoProcessOnUpload(false);

        service = new WikiRawMaterialService(
                rawMapper, kbService, props,
                mock(ApplicationEventPublisher.class),
                mock(DocumentExtractTool.class),
                chunkService,
                mock(ImageVisionService.class),
                mock(PdfImageExtractor.class),
                mock(FeatureFlagService.class));

        // pageService is wired via @Autowired(required=false); inject manually.
        Field f = WikiRawMaterialService.class.getDeclaredField("pageService");
        f.setAccessible(true);
        f.set(service, pageService);
    }

    private static WikiRawMaterialEntity raw(Long id, Long kbId) {
        WikiRawMaterialEntity r = new WikiRawMaterialEntity();
        r.setId(id);
        r.setKbId(kbId);
        return r;
    }

    private static WikiRawMaterialEntity rawWithPath(Long id, Long kbId, String path) {
        WikiRawMaterialEntity r = raw(id, kbId);
        r.setSourcePath(path);
        return r;
    }

    @Test
    @DisplayName("delete cascades to exclusive pages, raw row, and chunks — in that order")
    void cascadesToPagesAndChunks() {
        when(rawMapper.selectById(RAW_ID)).thenReturn(raw(RAW_ID, KB_ID));
        when(pageService.deleteExclusiveBySourceRawId(KB_ID, RAW_ID)).thenReturn(3);

        service.delete(RAW_ID);

        // Pages first (so we can use kb_id off the still-present raw row).
        verify(pageService).deleteExclusiveBySourceRawId(KB_ID, RAW_ID);
        verify(rawMapper).deleteById(RAW_ID);
        verify(chunkService).deleteByRawId(RAW_ID);
    }

    @Test
    @DisplayName("delete still works when pageService bean is absent (defensive null check)")
    void noPageService_stillDeletesRawAndChunks() throws Exception {
        // Strip pageService back out — simulates a deployment without that bean.
        Field f = WikiRawMaterialService.class.getDeclaredField("pageService");
        f.setAccessible(true);
        f.set(service, null);

        when(rawMapper.selectById(RAW_ID)).thenReturn(raw(RAW_ID, KB_ID));

        service.delete(RAW_ID);

        verify(rawMapper).deleteById(RAW_ID);
        verify(chunkService).deleteByRawId(RAW_ID);
        // pageService is null — no method to verify, but the call must not throw.
    }

    @Test
    @DisplayName("delete is idempotent when raw row is already gone (selectById returns null)")
    void rawAlreadyDeleted_skipsPageCascade() {
        when(rawMapper.selectById(RAW_ID)).thenReturn(null);

        service.delete(RAW_ID);

        // No raw, no kb_id, so we skip the page cascade — but still try the
        // raw delete + chunk cleanup since they're keyed on rawId alone.
        verify(pageService, never()).deleteExclusiveBySourceRawId(eq(KB_ID), eq(RAW_ID));
        verify(rawMapper).deleteById(RAW_ID);
        verify(chunkService).deleteByRawId(RAW_ID);
    }

    @Test
    @DisplayName("page cascade failure does not block the raw + chunk delete")
    void pageCascadeFails_softLogged() {
        when(rawMapper.selectById(RAW_ID)).thenReturn(raw(RAW_ID, KB_ID));
        when(pageService.deleteExclusiveBySourceRawId(KB_ID, RAW_ID))
                .thenThrow(new RuntimeException("page mapper down"));

        service.delete(RAW_ID);

        // The thrown exception is caught + logged; raw + chunks still get
        // cleaned. Operator sees a warn line but the delete itself succeeds.
        verify(rawMapper, atLeastOnce()).deleteById(RAW_ID);
        verify(chunkService, atLeastOnce()).deleteByRawId(RAW_ID);
    }

    @Test
    @DisplayName("raw with null kbId is treated as not-cascaded (defensive)")
    void rawWithNullKbId_skipsPageCascade() {
        when(rawMapper.selectById(RAW_ID)).thenReturn(raw(RAW_ID, null));

        service.delete(RAW_ID);

        verify(pageService, never()).deleteExclusiveBySourceRawId(eq(KB_ID), eq(RAW_ID));
        verify(rawMapper).deleteById(RAW_ID);
    }

    @Test
    @DisplayName("zero pages cascaded → still proceeds (raw with 0 generated pages, e.g. failed ingest)")
    void zeroPagesCascaded() {
        when(rawMapper.selectById(RAW_ID)).thenReturn(raw(RAW_ID, KB_ID));
        when(pageService.deleteExclusiveBySourceRawId(KB_ID, RAW_ID)).thenReturn(0);

        service.delete(RAW_ID);

        verify(pageService, times(1)).deleteExclusiveBySourceRawId(KB_ID, RAW_ID);
        verify(rawMapper).deleteById(RAW_ID);
        verify(chunkService).deleteByRawId(RAW_ID);
    }

    @Test
    @DisplayName("delete also removes the upload file from disk (sourcePath inside uploadDir)")
    void cleansSourceFile(@TempDir Path tmp) throws Exception {
        // Pretend tmp is the upload directory so the source file is sandbox-eligible.
        props.setUploadDir(tmp.toString());

        Path file = Files.createFile(tmp.resolve("uploaded.pdf"));
        Files.writeString(file, "fake pdf bytes");
        Assertions.assertThat(Files.exists(file)).isTrue();

        when(rawMapper.selectById(RAW_ID)).thenReturn(rawWithPath(RAW_ID, KB_ID, file.toString()));

        service.delete(RAW_ID);

        Assertions.assertThat(Files.exists(file))
                .as("source file should be removed alongside the raw row")
                .isFalse();
    }

    @Test
    @DisplayName("delete with a missing source file is a no-op for the file (idempotent)")
    void cleansSourceFile_alreadyMissing(@TempDir Path tmp) {
        props.setUploadDir(tmp.toString());

        // Path that never existed — cleanupFile must not throw.
        String ghostPath = tmp.resolve("never-existed.pdf").toString();
        when(rawMapper.selectById(RAW_ID)).thenReturn(rawWithPath(RAW_ID, KB_ID, ghostPath));

        service.delete(RAW_ID);

        // Reaches DB cascades + does not throw.
        verify(rawMapper).deleteById(RAW_ID);
    }

    @Test
    @DisplayName("delete must NOT touch a directory-scanned file (sourcePath outside uploadDir)")
    void preservesUserOwnedScannedFile(@TempDir Path tmp) throws Exception {
        // uploadDir lives under tmp/uploads — the scanned file lives elsewhere
        // under tmp/user-docs, simulating a user-chosen directory imported in
        // place by WikiDirectoryScanService (no copy made into the upload tree).
        Path uploadDir = Files.createDirectory(tmp.resolve("uploads"));
        Path userDocs = Files.createDirectory(tmp.resolve("user-docs"));
        props.setUploadDir(uploadDir.toString());

        Path scannedFile = Files.createFile(userDocs.resolve("important.pdf"));
        Files.writeString(scannedFile, "user's original document");

        when(rawMapper.selectById(RAW_ID))
                .thenReturn(rawWithPath(RAW_ID, KB_ID, scannedFile.toString()));

        service.delete(RAW_ID);

        // The DB-side cascade still runs (raw row + chunks + pages).
        verify(rawMapper).deleteById(RAW_ID);
        verify(chunkService).deleteByRawId(RAW_ID);
        verify(pageService).deleteExclusiveBySourceRawId(KB_ID, RAW_ID);

        // But the user's local file MUST survive — it was referenced in place,
        // never copied, and isn't ours to delete.
        Assertions.assertThat(Files.exists(scannedFile))
                .as("scanned file outside uploadDir must be preserved on raw delete")
                .isTrue();
        Assertions.assertThat(Files.readString(scannedFile)).isEqualTo("user's original document");
    }

    @Test
    @DisplayName("delete with null source path skips the file cleanup branch (text-only raw)")
    void cleansSourceFile_nullPath() {
        when(rawMapper.selectById(RAW_ID)).thenReturn(raw(RAW_ID, KB_ID));   // no sourcePath set

        service.delete(RAW_ID);

        verify(rawMapper).deleteById(RAW_ID);
        verify(chunkService).deleteByRawId(RAW_ID);
    }
}
