package vip.mate.agent.graph.executor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ToolResultStoragePreviewTest {
    @Test void spillKeepsTailSummaryWithoutReexecutingOrLosingFullResult(@TempDir Path root) throws Exception {
        var props=new ToolResultProperties();props.setStorageBaseDir(root.toString());
        String stdout="record\n".repeat(4000)+"SUMMARY_JSON:{\"rows\":150,\"total\":450}\n";
        String raw=new ObjectMapper().writeValueAsString(Map.of("exitCode",0,"stdout",stdout,"stderr",""));
        var storage=new ToolResultStorage(props);
        String preview=storage.persistIfOversized(raw,"execute_code","call","conversation",null);
        assertTrue(preview.contains("SUMMARY_JSON"),"A bounded tail must preserve the actual final aggregate");
        assertTrue(preview.contains("INCOMPLETE"));assertTrue(preview.contains("read_file"));
        assertTrue(preview.length()<2500);
        try(var files=Files.walk(root)) {
            var stored=files.filter(Files::isRegularFile).toList();assertEquals(1,stored.size());
            assertEquals(raw,Files.readString(stored.getFirst()));
        }
    }
}
