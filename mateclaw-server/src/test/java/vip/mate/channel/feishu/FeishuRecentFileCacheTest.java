package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the per-chat recent-file cache:
 *
 * <ul>
 *   <li>{@link FeishuChannelAdapter#loadRecentFilesFromDisk(Path, long)} — disk scan with
 *       explicit dir and TTL cutoff so tests never touch the real filesystem or depend on
 *       wall-clock time.</li>
 *   <li>{@link FeishuChannelAdapter#injectRecentFiles} — Caffeine cache-hit and cache-miss
 *       (disk fallback) paths, duplicate dedup, image vs file part typing.</li>
 * </ul>
 */
class FeishuRecentFileCacheTest {

    // ==================== helpers ====================

    private static FeishuChannelAdapter newAdapter() {
        ChannelEntity e = new ChannelEntity();
        e.setId(1L);
        e.setChannelType("feishu");
        e.setConfigJson("{\"app_id\":\"cli_test\",\"app_secret\":\"x\"}");
        return new FeishuChannelAdapter(
                e, mock(ChannelMessageRouter.class), new ObjectMapper(),
                null, null, null, null, null, null, null);
    }

    /** Write a tiny file and stamp its last-modified time. */
    private static Path touch(Path dir, String name, long lastModifiedMs) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, "x");
        Files.setLastModifiedTime(file, FileTime.fromMillis(lastModifiedMs));
        return file;
    }

    private static final long NOW = System.currentTimeMillis();
    private static final long FIVE_MIN_AGO = NOW - 5 * 60_000L;
    private static final long TEN_MIN_AGO  = NOW - 10 * 60_000L;
    private static final long OLD           = NOW - 90 * 60_000L; // > 60-min TTL

    // ==================== loadRecentFilesFromDisk ====================

    @Test
    void loadFromDisk_nonExistentDir_returnsEmpty(@TempDir Path tmp) {
        FeishuChannelAdapter a = newAdapter();
        List<FeishuChannelAdapter.RecentFileEntry> result =
                a.loadRecentFilesFromDisk(tmp.resolve("no-such"), NOW - 60 * 60_000L);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadFromDisk_emptyDir_returnsEmpty(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("chat"); Files.createDirectories(dir);
        FeishuChannelAdapter a = newAdapter();
        assertTrue(a.loadRecentFilesFromDisk(dir, NOW - 60 * 60_000L).isEmpty());
    }

    @Test
    void loadFromDisk_freshFiles_returnedSortedNewestFirst(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("chat"); Files.createDirectories(dir);
        touch(dir, "1000000000001_a.txt", TEN_MIN_AGO);
        touch(dir, "1000000000002_b.txt", FIVE_MIN_AGO);

        FeishuChannelAdapter a = newAdapter();
        long cutoff = NOW - 60 * 60_000L;
        List<FeishuChannelAdapter.RecentFileEntry> result = a.loadRecentFilesFromDisk(dir, cutoff);

        assertEquals(2, result.size());
        // newest first
        assertEquals("b.txt", result.get(0).fileName());
        assertEquals("a.txt", result.get(1).fileName());
    }

    @Test
    void loadFromDisk_staleFiles_excluded(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("chat"); Files.createDirectories(dir);
        touch(dir, "1000000000001_old.txt", OLD);

        FeishuChannelAdapter a = newAdapter();
        long cutoff = NOW - 60 * 60_000L;
        assertTrue(a.loadRecentFilesFromDisk(dir, cutoff).isEmpty());
    }

    @Test
    void loadFromDisk_mixFreshAndStale_onlyFreshReturned(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("chat"); Files.createDirectories(dir);
        touch(dir, "1000000000001_fresh.pdf", FIVE_MIN_AGO);
        touch(dir, "1000000000002_stale.pdf", OLD);

        FeishuChannelAdapter a = newAdapter();
        long cutoff = NOW - 60 * 60_000L;
        List<FeishuChannelAdapter.RecentFileEntry> result = a.loadRecentFilesFromDisk(dir, cutoff);

        assertEquals(1, result.size());
        assertEquals("fresh.pdf", result.get(0).fileName());
    }

    @Test
    void loadFromDisk_moreThan5FreshFiles_cappedAtMax(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("chat"); Files.createDirectories(dir);
        for (int i = 1; i <= 7; i++) {
            touch(dir, "100000000000" + i + "_f" + i + ".txt", FIVE_MIN_AGO - i * 1000L);
        }

        FeishuChannelAdapter a = newAdapter();
        long cutoff = NOW - 60 * 60_000L;
        List<FeishuChannelAdapter.RecentFileEntry> result = a.loadRecentFilesFromDisk(dir, cutoff);

        assertEquals(5, result.size()); // RECENT_FILE_MAX_PER_CHAT
    }

    @Test
    void loadFromDisk_timestampPrefixStripped(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("chat"); Files.createDirectories(dir);
        touch(dir, "1777391026594_report.pdf", FIVE_MIN_AGO);
        // No-underscore name: no stripping
        touch(dir, "plain.pdf", TEN_MIN_AGO);

        FeishuChannelAdapter a = newAdapter();
        long cutoff = NOW - 60 * 60_000L;
        List<FeishuChannelAdapter.RecentFileEntry> result = a.loadRecentFilesFromDisk(dir, cutoff);

        assertEquals(2, result.size());
        // newest first is the one with timestamp prefix
        assertEquals("report.pdf", result.get(0).fileName());
        assertEquals("plain.pdf", result.get(1).fileName());
    }

    @Test
    void loadFromDisk_contentTypeGuessingByExtension(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("chat"); Files.createDirectories(dir);
        touch(dir, "1000000000001_doc.pdf", FIVE_MIN_AGO);
        touch(dir, "1000000000002_img.png", TEN_MIN_AGO);
        touch(dir, "1000000000003_mystery.xyz", OLD - 1); // stale, should be excluded

        FeishuChannelAdapter a = newAdapter();
        long cutoff = NOW - 60 * 60_000L;
        List<FeishuChannelAdapter.RecentFileEntry> result = a.loadRecentFilesFromDisk(dir, cutoff);

        assertEquals(2, result.size());
        assertEquals("application/pdf", result.get(0).contentType());
        assertEquals("image/png", result.get(1).contentType());
    }

    // ==================== injectRecentFiles ====================

    @Test
    void injectRecentFiles_cacheHit_injectsFromCache(@TempDir Path tmp) {
        FeishuChannelAdapter a = newAdapter();
        a.chatUploadsRoot = tmp; // no disk files — only cache should be hit

        String convId = "feishu:oc_test123";
        a.recentFileCache.put(convId, List.of(
                new FeishuChannelAdapter.RecentFileEntry("report.pdf", "/tmp/report.pdf",
                        null, "application/pdf")
        ));

        List<MessageContentPart> parts = new ArrayList<>();
        String text = a.injectRecentFiles(convId, parts, "请分析");

        assertEquals(1, parts.size());
        assertEquals("file", parts.get(0).getType());
        assertEquals("report.pdf", parts.get(0).getFileName());
        assertTrue(text.contains("[用户发送了文件: report.pdf]"));
    }

    @Test
    void injectRecentFiles_cacheMiss_diskHasFreshFile_fallsBackToDisk(@TempDir Path tmp) throws IOException {
        FeishuChannelAdapter a = newAdapter();
        a.chatUploadsRoot = tmp;

        String convId = "feishu:oc_groupX";
        Path convDir = tmp.resolve(convId);
        Files.createDirectories(convDir);
        Files.writeString(convDir.resolve("1000000000001_summary.txt"), "content");

        List<MessageContentPart> parts = new ArrayList<>();
        String text = a.injectRecentFiles(convId, parts, "帮我看看");

        assertEquals(1, parts.size(), "disk fallback should inject the file");
        assertEquals("summary.txt", parts.get(0).getFileName());
        assertTrue(text.contains("[用户发送了文件: summary.txt]"));
    }

    @Test
    void injectRecentFiles_cacheMiss_diskEmpty_noChange(@TempDir Path tmp) {
        FeishuChannelAdapter a = newAdapter();
        a.chatUploadsRoot = tmp;

        List<MessageContentPart> parts = new ArrayList<>();
        String original = "帮我看看";
        String text = a.injectRecentFiles("feishu:oc_empty", parts, original);

        assertTrue(parts.isEmpty());
        assertEquals(original, text);
    }

    @Test
    void injectRecentFiles_duplicatePathSkipped(@TempDir Path tmp) {
        FeishuChannelAdapter a = newAdapter();
        a.chatUploadsRoot = tmp;

        String convId = "feishu:oc_dedup";
        String existingPath = "/some/path/file.pdf";
        a.recentFileCache.put(convId, List.of(
                new FeishuChannelAdapter.RecentFileEntry("file.pdf", existingPath,
                        null, "application/pdf")
        ));

        // part already carrying the same path
        MessageContentPart existing = MessageContentPart.file("key", "file.pdf", null);
        existing.setPath(existingPath);
        List<MessageContentPart> parts = new ArrayList<>(List.of(existing));

        a.injectRecentFiles(convId, parts, "");

        // size unchanged — duplicate suppressed
        assertEquals(1, parts.size());
    }

    @Test
    void injectRecentFiles_imageEntry_setsTypeImage(@TempDir Path tmp) {
        FeishuChannelAdapter a = newAdapter();
        a.chatUploadsRoot = tmp;

        String convId = "feishu:oc_img";
        a.recentFileCache.put(convId, List.of(
                new FeishuChannelAdapter.RecentFileEntry("photo.png", "/tmp/photo.png",
                        null, "image/png")
        ));

        List<MessageContentPart> parts = new ArrayList<>();
        a.injectRecentFiles(convId, parts, "");

        assertEquals(1, parts.size());
        assertEquals("image", parts.get(0).getType());
    }

    @Test
    void injectRecentFiles_nullTextContent_handledGracefully(@TempDir Path tmp) {
        FeishuChannelAdapter a = newAdapter();
        a.chatUploadsRoot = tmp;

        String convId = "feishu:oc_nulltext";
        a.recentFileCache.put(convId, List.of(
                new FeishuChannelAdapter.RecentFileEntry("data.csv", "/tmp/data.csv",
                        null, "text/csv")
        ));

        List<MessageContentPart> parts = new ArrayList<>();
        String text = a.injectRecentFiles(convId, parts, null);

        assertFalse(text.isBlank());
        assertTrue(text.contains("data.csv"));
    }
}
