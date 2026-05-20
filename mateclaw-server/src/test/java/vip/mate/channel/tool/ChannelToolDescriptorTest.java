package vip.mate.channel.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the descriptor's compact-record validation:
 * <ul>
 *   <li>blank name / description / schema → IllegalArgumentException</li>
 *   <li>mutating implies enabledByDefault=false regardless of caller intent</li>
 * </ul>
 */
class ChannelToolDescriptorTest {

    @Test
    @DisplayName("non-mutating tool keeps the caller's enabledByDefault")
    void readToolHonorsEnabledByDefault() {
        ChannelToolDescriptor enabled = new ChannelToolDescriptor(
                "feishu_calendar_list", "List calendars", "List user's Feishu calendars",
                "{\"type\":\"object\"}", false, true);
        assertTrue(enabled.enabledByDefault());

        ChannelToolDescriptor disabled = new ChannelToolDescriptor(
                "feishu_calendar_list", "List", "List", "{\"type\":\"object\"}", false, false);
        assertFalse(disabled.enabledByDefault());
    }

    @Test
    @DisplayName("mutating tool always lands disabled even when caller asks for enabled")
    void mutatingForcesDisabledDefault() {
        ChannelToolDescriptor d = new ChannelToolDescriptor(
                "feishu_calendar_create_event", "Create event",
                "Creates an event on the user's calendar",
                "{\"type\":\"object\"}", true, true);
        assertFalse(d.enabledByDefault(),
                "mutating tools must start disabled — write surfaces should require explicit opt-in");
        assertTrue(d.mutating());
    }

    @Test
    @DisplayName("blank name / description / inputSchema rejected at construction")
    void blankFieldsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChannelToolDescriptor("", "x", "x", "{}", false, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ChannelToolDescriptor("x", "x", "", "{}", false, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ChannelToolDescriptor("x", "x", "x", "  ", false, true));
    }

    @Test
    @DisplayName("equals / hashCode are record-default (value semantics)")
    void valueSemantics() {
        ChannelToolDescriptor a = new ChannelToolDescriptor(
                "a", "A", "desc", "{}", false, true);
        ChannelToolDescriptor b = new ChannelToolDescriptor(
                "a", "A", "desc", "{}", false, true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
