package vip.mate.tool.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EditFileNewlineTest {
    @TempDir Path root;
    private final EditFileTool tool = new EditFileTool(mock(vip.mate.i18n.I18nService.class));
    private String edit(String content, String oldText, String newText, boolean all) throws Exception {
        Path file = root.resolve("sample.txt"); Files.writeString(file, content);
        tool.edit_file(file.toString(), oldText, newText, all, null);
        return Files.readString(file);
    }
    @Test void lfMatchPreservesCrlf() throws Exception {
        assertEquals("head\r\nx\r\ny\r\ntail\r\n", edit("head\r\na\r\nb\r\ntail\r\n", "a\nb", "x\ny", false));
    }
    @Test void singleLineReplacementUsesFileStyle() throws Exception {
        assertEquals("x\r\ny\r\nb\r\n", edit("a\r\nb\r\n", "a", "x\ny", false));
    }
    @Test void replacesFirstOrAll() throws Exception {
        assertEquals("x\r\ny\r\na\r\nb", edit("a\r\nb\r\na\r\nb", "a\nb", "x\ny", false));
        assertEquals("x\r\ny\r\nx\r\ny", edit("a\r\nb\r\na\r\nb", "a\nb", "x\ny", true));
    }
    @Test void mixedUntouchedRegionsRetainTheirBytes() throws Exception {
        assertEquals("head\nx\r\ny\ntail\r", edit("head\na\r\nb\ntail\r", "a\nb", "x\ny", false));
    }
    @Test void crlfNeedleMatchesLfFileAndMixedSpanReusesEachEnding() throws Exception {
        assertEquals("x\ny\n", edit("a\nb\n", "a\r\nb", "x\r\ny", false));
        assertEquals("x\r\ny\nz\rtail", edit("a\r\nb\nc\rtail", "a\nb\nc", "x\ny\nz", false));
    }
    @Test void lfFilesNormalizeReplacementAndMissDoesNotWrite() throws Exception {
        assertEquals("x\ny\nb\n", edit("a\nb\n", "a", "x\r\ny", false));
        assertEquals("a\r\nb", edit("a\r\nb", "absent", "x", true));
    }
}
