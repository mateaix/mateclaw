package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReasoningContentCacheTest {

    @AfterEach
    void cleanup() {
        ReasoningContentCache.clear();
    }

    @Test
    @DisplayName("Store and retrieve reasoning content by tool_call IDs")
    void storeAndGet() {
        List<String> ids = List.of("call_1", "call_2");
        ReasoningContentCache.store(ids, "thinking content here");

        assertEquals("thinking content here", ReasoningContentCache.get(ids));
    }

    @Test
    @DisplayName("Cache key is order-independent (sorted tool_call IDs)")
    void orderIndependent() {
        ReasoningContentCache.store(List.of("call_b", "call_a"), "content");

        assertEquals("content", ReasoningContentCache.get(List.of("call_a", "call_b")));
    }

    @Test
    @DisplayName("Miss returns null")
    void cacheMiss() {
        assertNull(ReasoningContentCache.get(List.of("nonexistent")));
    }

    @Test
    @DisplayName("Empty/null tool_call IDs are no-ops")
    void emptyIds() {
        ReasoningContentCache.store(List.of(), "content");
        ReasoningContentCache.store(null, "content");
        assertEquals(0, ReasoningContentCache.size());
    }

    @Test
    @DisplayName("Blank/null reasoning content is not cached")
    void blankContent() {
        ReasoningContentCache.store(List.of("call_1"), "");
        ReasoningContentCache.store(List.of("call_1"), "  ");
        ReasoningContentCache.store(List.of("call_1"), null);
        assertEquals(0, ReasoningContentCache.size());
    }

    @Test
    @DisplayName("Clear removes all entries")
    void clearAll() {
        ReasoningContentCache.store(List.of("call_1"), "content1");
        ReasoningContentCache.store(List.of("call_2"), "content2");
        assertEquals(2, ReasoningContentCache.size());

        ReasoningContentCache.clear();
        assertEquals(0, ReasoningContentCache.size());
        assertNull(ReasoningContentCache.get(List.of("call_1")));
    }
}
