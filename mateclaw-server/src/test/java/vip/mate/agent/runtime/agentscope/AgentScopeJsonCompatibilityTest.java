package vip.mate.agent.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.util.JacksonJsonCodec;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards the 2.0.5 JSON fixes used by native SDK state and tool arguments. */
class AgentScopeJsonCompatibilityTest {
    private final JacksonJsonCodec codec = new JacksonJsonCodec();

    @Test
    void rejectsTrailingJsonInsteadOfSilentlyAcceptingPartialToolArguments() {
        assertThrows(RuntimeException.class,
                () -> codec.fromJson("{\"value\":1} {\"value\":2}", Map.class));
        assertThrows(RuntimeException.class,
                () -> codec.fromJson("{\"value\":1} false", new TypeReference<Map<String, Object>>() {}));
    }

    @Test
    void serializedStringsRoundTripWithoutBeingReinterpretedAsRawJson() {
        for (String value : new String[] {"", "null", "true", "123", "{}", "[]",
                "\"quoted\"", "line\nnext\ttab", "中文 😀", "\\u0041"}) {
            assertEquals(value, codec.fromJson(codec.toJson(value), String.class), value);
        }
    }
}
