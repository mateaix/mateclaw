package vip.mate.memory;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.memory.spi.MemoryProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the token-budgeted system-prompt block assembly in
 * {@link MemoryManager} — provider order is priority order, later providers
 * drop whole once the budget is spent, a partially fitting block truncates
 * at a line boundary.
 */
class MemoryManagerBudgetTest {

    /** CJK chars estimate ≈ 1 token each, which makes budgets easy to reason about. */
    private static final String BLOCK_A = "甲".repeat(300);
    private static final String BLOCK_B = "乙".repeat(300);

    private static MemoryProvider provider(String id, String block) {
        return new MemoryProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String systemPromptBlock(Long agentId) {
                return block;
            }
        };
    }

    private static MemoryManager manager(MemoryProvider... providers) {
        ObjectProvider<MeterRegistry> noRegistry = new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MeterRegistry getIfAvailable() {
                return null;
            }
        };
        return new MemoryManager(List.of(providers), new MemoryProperties(), noRegistry);
    }

    @Test
    @DisplayName("unbudgeted call joins every provider block — previous behavior")
    void unbudgetedJoinsAll() {
        MemoryManager manager = manager(provider("a", BLOCK_A), provider("b", BLOCK_B));
        String result = manager.buildSystemPromptBlock(1L);
        assertTrue(result.contains(BLOCK_A));
        assertTrue(result.contains(BLOCK_B));
    }

    @Test
    @DisplayName("budget exhausted after the first block → later provider dropped whole")
    void budgetDropsLaterProviders() {
        MemoryManager manager = manager(provider("a", BLOCK_A), provider("b", BLOCK_B));
        // 300 CJK chars ≈ 300 tokens; 350 fits block A but not A+B.
        String result = manager.buildSystemPromptBlock(1L, 350);
        assertTrue(result.contains(BLOCK_A));
        assertFalse(result.contains("乙"));
    }

    @Test
    @DisplayName("single multi-line block over budget truncates at a line boundary with a marker")
    void oversizedBlockTruncatesAtLineBoundary() {
        String multiLine = ("行".repeat(100) + "\n").repeat(10).trim();
        MemoryManager manager = manager(provider("a", multiLine));
        String result = manager.buildSystemPromptBlock(1L, 350);
        assertTrue(result.contains("[memory truncated to fit the model context window]"));
        // Only whole lines are kept — every kept content line is the full 100-char line.
        for (String line : result.split("\n")) {
            if (line.startsWith("行")) {
                assertEquals(100, line.length());
            }
        }
        assertTrue(result.length() < multiLine.length());
    }

    @Test
    @DisplayName("zero budget yields an empty block, not an exception")
    void zeroBudgetYieldsEmpty() {
        MemoryManager manager = manager(provider("a", BLOCK_A));
        assertEquals("", manager.buildSystemPromptBlock(1L, 0));
    }
}
