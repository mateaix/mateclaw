package vip.mate.channel.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.model.ToolEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pin the {@code source="channel"} classification — the picker UI
 * shows "Channel · {channelName}" only when this branch fires; a
 * regression here silently lumps channel tools into the Built-in
 * bucket.
 */
class AvailableToolChannelSourceTest {

    @Test
    @DisplayName("fromChannel sets source=channel + group label includes the channel name")
    void fromChannelClassifiesAsChannel() {
        ToolEntity row = new ToolEntity();
        row.setName("feishu_calendar_list_events_c99");
        row.setDisplayName("List calendar events (My Feishu Bot)");
        row.setDescription("List events on the user's Feishu calendar");
        row.setToolType("channel");
        row.setChannelId(99L);
        row.setEnabled(true);

        AvailableToolDTO dto = AvailableToolDTO.fromChannel(row);
        assertEquals("channel", dto.getSource());
        assertEquals(99L, dto.getProviderId());
        assertEquals("My Feishu Bot", dto.getProviderName());
        assertEquals("Channel · My Feishu Bot", dto.getGroup());
        assertEquals("channel:99", dto.getGroupId());
        assertEquals("feishu_calendar_list_events_c99", dto.getName());
    }

    @Test
    @DisplayName("fromChannel handles missing channel name suffix gracefully (no NPE, default group)")
    void fromChannelHandlesMissingChannelName() {
        ToolEntity row = new ToolEntity();
        row.setName("feishu_x");
        row.setDisplayName("display without parens");
        row.setDescription("desc");
        row.setToolType("channel");
        row.setChannelId(null);
        AvailableToolDTO dto = AvailableToolDTO.fromChannel(row);
        assertEquals("channel", dto.getSource());
        assertEquals("Channel", dto.getGroup());
        assertEquals("channel", dto.getGroupId());
    }
}
