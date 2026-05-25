package vip.mate.channel.feishu.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.tool.ChannelToolDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the descriptor catalog: read tools default on, write tools land
 * disabled, names are stable (downstream rule IDs and channel-tool
 * UI state bind on them).
 */
class FeishuToolCatalogTest {

    @Test
    @DisplayName("catalog returns the agreed 3-tool initial set")
    void catalogShape() {
        List<ChannelToolDescriptor> ds = FeishuToolCatalog.descriptors();
        Set<String> names = ds.stream().map(ChannelToolDescriptor::name).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                FeishuToolCatalog.TOOL_LIST_EVENTS,
                FeishuToolCatalog.TOOL_DOC_READ,
                FeishuToolCatalog.TOOL_DOC_CREATE
        ), names);
    }

    @Test
    @DisplayName("read tools land default-enabled, write tool lands default-disabled")
    void readWriteDefaults() {
        Map<String, ChannelToolDescriptor> byName = FeishuToolCatalog.descriptors().stream()
                .collect(java.util.stream.Collectors.toMap(ChannelToolDescriptor::name, d -> d));

        assertFalse(byName.get(FeishuToolCatalog.TOOL_LIST_EVENTS).mutating());
        assertTrue(byName.get(FeishuToolCatalog.TOOL_LIST_EVENTS).enabledByDefault());

        assertFalse(byName.get(FeishuToolCatalog.TOOL_DOC_READ).mutating());
        assertTrue(byName.get(FeishuToolCatalog.TOOL_DOC_READ).enabledByDefault());

        assertTrue(byName.get(FeishuToolCatalog.TOOL_DOC_CREATE).mutating());
        assertFalse(byName.get(FeishuToolCatalog.TOOL_DOC_CREATE).enabledByDefault(),
                "write tools must be disabled by default so a freshly-installed channel doesn't auto-create docs");
    }

    @Test
    @DisplayName("every descriptor carries a non-blank JSON schema")
    void schemasNonBlank() {
        for (ChannelToolDescriptor d : FeishuToolCatalog.descriptors()) {
            assertTrue(d.inputSchema().contains("type"),
                    d.name() + " schema should look like JSON Schema; got: " + d.inputSchema());
        }
    }
}
