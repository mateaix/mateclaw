package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.stt.SttService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pin the Feishu inbound-audio STT contract.
 *
 * <p>Why this matters: Feishu — unlike WeCom and DingTalk — does NOT
 * include ASR text in its inbound webhook payload, only an opaque
 * {@code file_key}. Without the {@code transcribeInboundAudio} hop the
 * agent sees the literal text {@code "[音频]"} and cannot reason about
 * what the user said. That was the production gap reported when a user
 * sent a voice message to the bot.
 *
 * <p>The contract this test pins:
 * <ol>
 *   <li>Successful STT returns the transcript, which the audio branch
 *       of {@code extractContentParts} prepends as a text part so the
 *       prompt builder sees real content.</li>
 *   <li>STT failure (no provider, empty text, exception) returns
 *       {@code null} — never throws, never blocks the agent from
 *       seeing the audio part.</li>
 *   <li>STT not wired in (legacy 3-arg ctor, tests) returns
 *       {@code null} silently — degraded but not broken.</li>
 *   <li>Empty / missing audio file is detected before the SttService
 *       call so we don't bill providers for zero-byte requests.</li>
 * </ol>
 */
class FeishuAudioSttTest {

    @TempDir
    Path tmpDir;

    private SttService sttService;

    @BeforeEach
    void setUp() {
        sttService = mock(SttService.class);
    }

    @Test
    @DisplayName("STT success → transcript returned for prepending as text part")
    void transcriptReturnedOnSuccess() throws Exception {
        Path audioFile = tmpDir.resolve("voice.opus");
        Files.write(audioFile, "fake-opus-bytes".getBytes());

        when(sttService.transcribe(any(), eq("voice.opus"), eq("audio/opus"), eq(null)))
                .thenReturn(Map.of("success", true, "text", "你好，能帮我查一下天气吗"));

        FeishuChannelAdapter adapter = adapterWithStt(sttService);
        FeishuChannelAdapter.DownloadedResource dl = new FeishuChannelAdapter.DownloadedResource(
                audioFile.toAbsolutePath().toString(),
                "/api/v1/files/generated/some-id",
                "voice.opus",
                "audio/opus");

        String transcript = adapter.transcribeInboundAudio(dl);
        assertEquals("你好，能帮我查一下天气吗", transcript);
    }

    @Test
    @DisplayName("STT failure → null, agent still sees audio part (no throw)")
    void nullOnSttFailure() throws Exception {
        Path audioFile = tmpDir.resolve("voice.opus");
        Files.write(audioFile, "fake-opus-bytes".getBytes());

        when(sttService.transcribe(any(), any(), any(), any()))
                .thenReturn(Map.of("success", false, "error", "no provider"));

        FeishuChannelAdapter adapter = adapterWithStt(sttService);
        FeishuChannelAdapter.DownloadedResource dl = new FeishuChannelAdapter.DownloadedResource(
                audioFile.toAbsolutePath().toString(),
                null, "voice.opus", "audio/opus");

        assertNull(adapter.transcribeInboundAudio(dl),
                "STT failure must return null, not throw — agent gets [音频] placeholder only");
    }

    @Test
    @DisplayName("empty transcript text → null (don't inject blank text parts)")
    void nullOnEmptyTranscript() throws Exception {
        Path audioFile = tmpDir.resolve("voice.opus");
        Files.write(audioFile, "fake-opus-bytes".getBytes());

        // Some STT providers return success=true with empty text for silence
        // or unsupported audio — those shouldn't pollute the prompt with a
        // blank text part.
        when(sttService.transcribe(any(), any(), any(), any()))
                .thenReturn(Map.of("success", true, "text", "   "));

        FeishuChannelAdapter adapter = adapterWithStt(sttService);
        FeishuChannelAdapter.DownloadedResource dl = new FeishuChannelAdapter.DownloadedResource(
                audioFile.toAbsolutePath().toString(),
                null, "voice.opus", "audio/opus");

        assertNull(adapter.transcribeInboundAudio(dl));
    }

    @Test
    @DisplayName("SttService missing (legacy ctor) → null, no NPE")
    void nullWhenSttServiceMissing() throws Exception {
        Path audioFile = tmpDir.resolve("voice.opus");
        Files.write(audioFile, "fake-opus-bytes".getBytes());

        FeishuChannelAdapter adapter = adapterWithStt(null);
        FeishuChannelAdapter.DownloadedResource dl = new FeishuChannelAdapter.DownloadedResource(
                audioFile.toAbsolutePath().toString(),
                null, "voice.opus", "audio/opus");

        assertNull(adapter.transcribeInboundAudio(dl));
    }

    @Test
    @DisplayName("null DownloadedResource → null, STT not called (download was disabled / failed)")
    void nullOnMissingDownload() {
        FeishuChannelAdapter adapter = adapterWithStt(sttService);

        assertNull(adapter.transcribeInboundAudio(null));
        // Verify we never billed the provider for a no-op.
        verify(sttService, never()).transcribe(any(), any(), any(), any());
    }

    @Test
    @DisplayName("empty audio file → null, no STT call (don't bill provider for 0 bytes)")
    void nullOnEmptyAudioFile() throws Exception {
        Path emptyFile = tmpDir.resolve("empty.opus");
        Files.write(emptyFile, new byte[0]);

        FeishuChannelAdapter adapter = adapterWithStt(sttService);
        FeishuChannelAdapter.DownloadedResource dl = new FeishuChannelAdapter.DownloadedResource(
                emptyFile.toAbsolutePath().toString(),
                null, "voice.opus", "audio/opus");

        assertNull(adapter.transcribeInboundAudio(dl));
        verify(sttService, never()).transcribe(any(), any(), any(), any());
    }

    @Test
    @DisplayName("missing file path → null, STT not called (download flag was off)")
    void nullOnMissingPath() {
        FeishuChannelAdapter adapter = adapterWithStt(sttService);
        FeishuChannelAdapter.DownloadedResource dl = new FeishuChannelAdapter.DownloadedResource(
                null, "/api/v1/files/generated/x", "voice.opus", "audio/opus");

        assertNull(adapter.transcribeInboundAudio(dl));
        verify(sttService, never()).transcribe(any(), any(), any(), any());
    }

    @Test
    @DisplayName("fileName/contentType from download propagate to SttService for provider routing")
    void fileNameAndMimePropagate() throws Exception {
        Path audioFile = tmpDir.resolve("custom.mp3");
        Files.write(audioFile, "fake".getBytes());

        Map<String, Object> success = new HashMap<>();
        success.put("success", true);
        success.put("text", "hi");
        when(sttService.transcribe(any(), eq("custom.mp3"), eq("audio/mpeg"), eq(null)))
                .thenReturn(success);

        FeishuChannelAdapter adapter = adapterWithStt(sttService);
        FeishuChannelAdapter.DownloadedResource dl = new FeishuChannelAdapter.DownloadedResource(
                audioFile.toAbsolutePath().toString(),
                null, "custom.mp3", "audio/mpeg");

        assertEquals("hi", adapter.transcribeInboundAudio(dl));
        // Strict matchers above (eq("custom.mp3"), eq("audio/mpeg")) are
        // what enforce propagation — if the helper had defaulted to opus,
        // the stub would have returned null and the assert would fail.
    }

    // ------------------------------------------------------------------
    // Test fixture
    // ------------------------------------------------------------------

    private static FeishuChannelAdapter adapterWithStt(SttService sttService) {
        ChannelEntity e = new ChannelEntity();
        e.setId(1L);
        e.setChannelType("feishu");
        e.setConfigJson("{\"app_id\":\"x\",\"app_secret\":\"y\"}");
        return new FeishuChannelAdapter(
                e,
                mock(ChannelMessageRouter.class),
                new ObjectMapper(),
                null, null, null, null, null, null,
                sttService);
    }
}
