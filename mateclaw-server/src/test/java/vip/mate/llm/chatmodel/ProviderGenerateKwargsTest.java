package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderGenerateKwargsTest {

    @Test
    @DisplayName("findOptionValue reads snake_case keys nested under chat_options")
    void findOptionValue_readsSnakeCaseNestedUnderChatOptionsSnakeCaseWrapper() {
        Map<String, Object> kwargs = Map.of(
                "chat_options", Map.of(
                        "enable_search", true,
                        "search_strategy", "pro"
                )
        );

        assertEquals(true, ProviderGenerateKwargs.findOptionValue(kwargs, "enableSearch"));
        assertEquals("pro", ProviderGenerateKwargs.findOptionValue(kwargs, "searchStrategy"));
    }
}
