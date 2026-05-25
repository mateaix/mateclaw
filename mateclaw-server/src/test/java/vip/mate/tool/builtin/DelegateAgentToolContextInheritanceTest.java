package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC-03 Lane C2 — covers {@link DelegateAgentTool#formatInheritedContext(List, int)},
 * the helper that builds the parent-context prefix injected into a child
 * agent's task when {@code inheritParentContext=true}.
 *
 * <p>Behavioral contracts under test:
 * <ul>
 *   <li>null / empty input → empty string (caller skips prefix injection cleanly).</li>
 *   <li>system messages are dropped — the child has its own system prompt
 *       and parent's identity-shaping instructions don't transfer.</li>
 *   <li>blank content is filtered.</li>
 *   <li>per-message char limit truncates; truncation marker exposes how
 *       many chars were dropped so debugging long-tool-result cases is
 *       straightforward.</li>
 *   <li>role labels are uppercased for distinct visual blocks in the
 *       child's system context.</li>
 * </ul>
 */
class DelegateAgentToolContextInheritanceTest {

    private static MessageEntity msg(String role, String content) {
        MessageEntity m = new MessageEntity();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    @DisplayName("null input → empty prefix (caller skips injection)")
    void nullInputReturnsEmpty() {
        assertEquals("", DelegateAgentTool.formatInheritedContext(null, 1000));
    }

    @Test
    @DisplayName("empty input → empty prefix")
    void emptyInputReturnsEmpty() {
        assertEquals("", DelegateAgentTool.formatInheritedContext(List.of(), 1000));
    }

    @Test
    @DisplayName("only system messages → empty prefix (system role is filtered)")
    void onlySystemMessagesReturnEmpty() {
        List<MessageEntity> messages = List.of(
                msg("system", "You are a helpful assistant."),
                msg("system", "Always respond in JSON.")
        );
        assertEquals("", DelegateAgentTool.formatInheritedContext(messages, 1000));
    }

    @Test
    @DisplayName("blank-content messages are filtered")
    void blankContentFiltered() {
        List<MessageEntity> messages = List.of(
                msg("user", ""),
                msg("user", "   "),
                msg("user", "real question?")
        );
        String prefix = DelegateAgentTool.formatInheritedContext(messages, 1000);
        // Only one usable message after filtering.
        assertTrue(prefix.contains("(1 message)"));
        assertTrue(prefix.contains("USER: real question?"));
    }

    @Test
    @DisplayName("happy path — alternating dialogue is formatted in order with role labels")
    void typicalDialogueFormatted() {
        List<MessageEntity> messages = List.of(
                msg("user", "What is context inheritance?"),
                msg("assistant", "Context inheritance is the follow-up fix."),
                msg("user", "Tell me about how it works specifically."),
                msg("assistant", "It inherits parent context into child agents.")
        );

        String prefix = DelegateAgentTool.formatInheritedContext(messages, 1000);

        assertTrue(prefix.startsWith("--- Parent conversation recent context (4 messages) ---"));
        assertTrue(prefix.endsWith("--- End of context ---"));
        // Role label uppercase + colon-space separator, in original order.
        int userIdx = prefix.indexOf("USER: What is context inheritance?");
        int asstIdx = prefix.indexOf("ASSISTANT: Context inheritance is the follow-up");
        int user2Idx = prefix.indexOf("USER: Tell me about how it works");
        assertTrue(userIdx > 0);
        assertTrue(asstIdx > userIdx, "messages must preserve chronological order");
        assertTrue(user2Idx > asstIdx, "messages must preserve chronological order");
    }

    @Test
    @DisplayName("singular vs plural — '1 message' not '1 messages'")
    void grammaticalNumber() {
        String oneMsg = DelegateAgentTool.formatInheritedContext(
                List.of(msg("user", "hi")), 1000);
        assertTrue(oneMsg.contains("(1 message)"), "header must say '1 message': " + oneMsg);
        assertFalse(oneMsg.contains("(1 messages)"));
    }

    @Test
    @DisplayName("oversized message body is truncated with explicit dropped-chars marker")
    void oversizedTruncated() {
        String longBody = "x".repeat(2000);
        List<MessageEntity> messages = List.of(msg("user", longBody));

        String prefix = DelegateAgentTool.formatInheritedContext(messages, 100);

        // Body kept = 100 chars. Marker mentions dropped chars (1900) so
        // anyone debugging "why is context cut off" sees the exact size.
        assertTrue(prefix.contains("[truncated, 1900 chars omitted]"),
                "truncation marker missing or wrong char count: " + prefix);
        // Marker must be appended, not prefixed; first usable char is still the body.
        assertTrue(prefix.contains("USER: " + "x".repeat(100) + "..."));
    }

    @Test
    @DisplayName("system messages mixed with dialogue → only dialogue survives")
    void systemMessagesFilteredFromMixedConversation() {
        List<MessageEntity> messages = List.of(
                msg("system", "Hidden system prompt"),
                msg("user", "Hi"),
                msg("assistant", "Hello!"),
                msg("system", "Another hidden instruction")
        );

        String prefix = DelegateAgentTool.formatInheritedContext(messages, 1000);

        assertFalse(prefix.contains("Hidden system prompt"),
                "system role must be filtered to avoid leaking parent identity instructions");
        assertFalse(prefix.contains("Another hidden instruction"));
        assertTrue(prefix.contains("USER: Hi"));
        assertTrue(prefix.contains("ASSISTANT: Hello!"));
        assertTrue(prefix.contains("(2 messages)"));
    }
}
