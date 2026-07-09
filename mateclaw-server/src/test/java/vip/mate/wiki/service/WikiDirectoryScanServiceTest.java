package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.model.WikiSourceGroupEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-group scan behavior added on {@link WikiDirectoryScanService}:
 * groupId tagging on newly-ingested / skip-hit files, and the incremental
 * vs full-scan distinction (full forces a reprocess of unchanged hits).
 */
class WikiDirectoryScanServiceTest {

    private static final Long KB_ID = 1L;
    private static final Long GROUP_ID = 42L;

    @TempDir
    Path tmpDir;

    private WikiRawMaterialService rawService;
    private WikiDirectoryScanService scanService;

    @BeforeEach
    void setUp() {
        rawService = mock(WikiRawMaterialService.class);
        WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
        WikiProperties properties = new WikiProperties();
        WikiSourcePathValidator pathValidator = new WikiSourcePathValidator(properties);
        scanService = new WikiDirectoryScanService(kbService, rawService, properties, pathValidator);
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tmpDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private WikiSourceGroupEntity group() {
        WikiSourceGroupEntity g = new WikiSourceGroupEntity();
        g.setId(GROUP_ID);
        g.setKbId(KB_ID);
        g.setPath(tmpDir.toString());
        return g;
    }

    private WikiRawMaterialEntity raw(Long id, Long groupId) {
        WikiRawMaterialEntity r = new WikiRawMaterialEntity();
        r.setId(id);
        r.setKbId(KB_ID);
        r.setGroupId(groupId);
        return r;
    }

    @Test
    @DisplayName("incremental scan: a freshly ingested file is tagged with the group")
    void incrementalScan_tagsNewRawWithGroup() throws IOException {
        writeFile("a.txt", "hello world");
        when(rawService.ingestTextFileFromScan(eq(KB_ID), eq("a.txt"), anyString(), eq("hello world")))
                .thenReturn(true);
        when(rawService.findBySourcePath(eq(KB_ID), anyString())).thenReturn(raw(100L, null));

        WikiDirectoryScanService.ScanResult result = scanService.scanGroup(KB_ID, group(), false);

        assertEquals(1, result.added());
        assertEquals(0, result.skipped());
        verify(rawService).updateGroup(100L, GROUP_ID);
        // incremental mode never force-reprocesses
        verify(rawService, never()).setLastProcessedHash(any(), any());
        verify(rawService, never()).reprocess(any());
    }

    @Test
    @DisplayName("incremental scan: a skip-hit file already in a group is left untouched")
    void incrementalScan_skipHitAlreadyGroupedNotRetagged() throws IOException {
        writeFile("a.txt", "unchanged content");
        when(rawService.ingestTextFileFromScan(eq(KB_ID), eq("a.txt"), anyString(), eq("unchanged content")))
                .thenReturn(false);
        when(rawService.findBySourcePath(eq(KB_ID), anyString())).thenReturn(raw(100L, 7L));

        WikiDirectoryScanService.ScanResult result = scanService.scanGroup(KB_ID, group(), false);

        assertEquals(0, result.added());
        assertEquals(1, result.skipped());
        verify(rawService, never()).updateGroup(anyLong(), anyLong());
        // incremental mode: no force reprocess even though the file was skipped
        verify(rawService, never()).reprocess(any());
    }

    @Test
    @DisplayName("incremental scan: a skip-hit file with no historic group gets backfilled")
    void incrementalScan_skipHitBackfillsMissingGroup() throws IOException {
        writeFile("a.txt", "unchanged content");
        when(rawService.ingestTextFileFromScan(eq(KB_ID), eq("a.txt"), anyString(), eq("unchanged content")))
                .thenReturn(false);
        when(rawService.findBySourcePath(eq(KB_ID), anyString())).thenReturn(raw(100L, null));

        scanService.scanGroup(KB_ID, group(), false);

        verify(rawService).updateGroup(100L, GROUP_ID);
        verify(rawService, never()).reprocess(any());
    }

    @Test
    @DisplayName("full scan: an unchanged skip-hit is force-reprocessed")
    void fullScan_forcesReprocessOfSkippedHit() throws IOException {
        writeFile("a.txt", "unchanged content");
        when(rawService.ingestTextFileFromScan(eq(KB_ID), eq("a.txt"), anyString(), eq("unchanged content")))
                .thenReturn(false);
        when(rawService.findBySourcePath(eq(KB_ID), anyString())).thenReturn(raw(100L, GROUP_ID));

        WikiDirectoryScanService.ScanResult result = scanService.scanGroup(KB_ID, group(), true);

        assertEquals(1, result.skipped());
        verify(rawService).setLastProcessedHash(100L, null);
        verify(rawService).reprocess(100L);
    }

    @Test
    @DisplayName("full scan: a raw still processing/pending is not force-reprocessed")
    void fullScan_skipsRawStillProcessing() throws IOException {
        writeFile("a.txt", "unchanged content");
        when(rawService.ingestTextFileFromScan(eq(KB_ID), eq("a.txt"), anyString(), eq("unchanged content")))
                .thenReturn(false);
        WikiRawMaterialEntity raw = raw(100L, GROUP_ID);
        raw.setProcessingStatus("processing");
        when(rawService.findBySourcePath(eq(KB_ID), anyString())).thenReturn(raw);

        scanService.scanGroup(KB_ID, group(), true);

        verify(rawService, never()).setLastProcessedHash(any(), any());
        verify(rawService, never()).reprocess(any());
    }

    @Test
    @DisplayName("full scan: a raw manually moved to another group is not force-reprocessed")
    void fullScan_skipsRawMovedToAnotherGroup() throws IOException {
        writeFile("a.txt", "unchanged content");
        when(rawService.ingestTextFileFromScan(eq(KB_ID), eq("a.txt"), anyString(), eq("unchanged content")))
                .thenReturn(false);
        when(rawService.findBySourcePath(eq(KB_ID), anyString())).thenReturn(raw(100L, 999L));

        scanService.scanGroup(KB_ID, group(), true);

        verify(rawService, never()).setLastProcessedHash(any(), any());
        verify(rawService, never()).reprocess(any());
    }

    @Test
    @DisplayName("fileFilter restricts scan results to matching filenames only")
    void fileFilter_restrictsToMatchingFiles() throws IOException {
        writeFile("keep.txt", "hello");
        Files.write(tmpDir.resolve("skip.pdf"), new byte[]{1, 2, 3});
        when(rawService.ingestTextFileFromScan(eq(KB_ID), eq("keep.txt"), anyString(), eq("hello")))
                .thenReturn(true);
        when(rawService.findBySourcePath(eq(KB_ID), anyString())).thenReturn(raw(100L, null));

        WikiSourceGroupEntity g = group();
        g.setFileFilter("*.txt");

        WikiDirectoryScanService.ScanResult result = scanService.scanGroup(KB_ID, g, false);

        assertEquals(1, result.scanned());
        assertEquals(1, result.added());
        verify(rawService, never()).ingestBinaryFileFromScan(any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("full scan: a freshly added file is not force-reprocessed again")
    void fullScan_freshFileNotDoubleReprocessed() throws IOException {
        writeFile("a.txt", "brand new content");
        when(rawService.ingestTextFileFromScan(eq(KB_ID), eq("a.txt"), anyString(), eq("brand new content")))
                .thenReturn(true);
        when(rawService.findBySourcePath(eq(KB_ID), anyString())).thenReturn(raw(100L, null));

        scanService.scanGroup(KB_ID, group(), true);

        verify(rawService, never()).setLastProcessedHash(any(), any());
        verify(rawService, never()).reprocess(any());
    }
}
