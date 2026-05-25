package vip.mate.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.workflow.model.WorkflowPayloadEntity;
import vip.mate.workflow.repository.WorkflowPayloadMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three-tier sizing contract on the payload store: small bytes go inline,
 * medium bytes spill to the filesystem, and runaway bytes are rejected at
 * write time so a single workflow run can't fill the disk silently.
 *
 * <p>The fs root is overridden to a tmpdir under {@code target/} per test
 * so parallel runs don't fight over a shared directory. Inline / hard-cap
 * thresholds are forced down to small values so the assertions don't have
 * to allocate megabytes.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:payload_store_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        // Force the size thresholds down so we don't have to allocate
        // hundreds of KB of fixture data inside this test.
        "mateclaw.workflow.payload.inline-max-bytes=64",
        "mateclaw.workflow.payload.hard-cap-bytes=512",
        "mateclaw.workflow.payload.fs.root=./target/test-payload-store"
})
class PayloadStoreFsTest {

    @Autowired private PayloadStore store;
    @Autowired private WorkflowPayloadMapper payloadMapper;

    @Test
    @DisplayName("Bytes ≤ inline-max-bytes are stored inline; row.content_bytes round-trips.")
    void smallBytesStoreInline() {
        byte[] body = "tiny payload".getBytes(StandardCharsets.UTF_8);
        String uri = store.storeBytes(99L, body, "text/plain");
        WorkflowPayloadEntity row = lookupRow(uri);
        assertEquals("inline", row.getStorageKind());
        assertNull(row.getStorageRef());
        assertNotNull(row.getContentBytes());
        assertArrayEquals(body, store.readBytes(uri));
    }

    @Test
    @DisplayName("Bytes between inline-max and hard-cap spill to the fs tier.")
    void mediumBytesSpillToFs() throws IOException {
        // 100 bytes — bigger than inline 64, smaller than cap 512.
        byte[] body = new byte[100];
        for (int i = 0; i < body.length; i++) body[i] = (byte) ('A' + (i % 26));

        String uri = store.storeBytes(99L, body, "application/octet-stream");
        WorkflowPayloadEntity row = lookupRow(uri);

        assertEquals("fs", row.getStorageKind());
        assertNull(row.getContentBytes());
        assertNotNull(row.getStorageRef(), "fs-tier rows must record their relative path");

        Path written = Path.of("./target/test-payload-store").toAbsolutePath()
                .resolve(row.getStorageRef());
        assertTrue(Files.exists(written),
                "fs payload was not actually written to disk at " + written);
        assertArrayEquals(body, Files.readAllBytes(written));
        assertArrayEquals(body, store.readBytes(uri));
    }

    @Test
    @DisplayName("Bytes over the hard cap are rejected at write time, no row, no file.")
    void oversizeBytesAreRejected() {
        byte[] tooBig = new byte[513]; // hard cap is 512
        long beforeRows = payloadMapper.selectCount(new LambdaQueryWrapper<>());

        PayloadStore.PayloadStoreException ex = assertThrows(
                PayloadStore.PayloadStoreException.class,
                () -> store.storeBytes(99L, tooBig, "application/octet-stream"));
        assertTrue(ex.getMessage().contains("hard cap"),
                "rejection message should call out the hard cap, was: " + ex.getMessage());

        long afterRows = payloadMapper.selectCount(new LambdaQueryWrapper<>());
        assertEquals(beforeRows, afterRows, "rejected payload must not have inserted a row");
    }

    private WorkflowPayloadEntity lookupRow(String uri) {
        WorkflowPayloadEntity row = payloadMapper.selectOne(
                new LambdaQueryWrapper<WorkflowPayloadEntity>()
                        .eq(WorkflowPayloadEntity::getPayloadUri, uri));
        assertNotNull(row, "row should have been inserted for uri " + uri);
        return row;
    }
}
