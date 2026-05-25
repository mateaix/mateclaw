package vip.mate.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-063r §2.3 (P0): regression guard — {@link LocaleAwareToolCallback} must
 * forward both the input string and the ToolContext to the wrapped callback.
 * Pre-fix this class only overrode {@code call(String)}, silently dropping the
 * context (and thus ChatOrigin) for every builtin tool.
 */
class LocaleAwareToolCallbackToolContextTest {

    @Test
    void callWithToolContext_forwardsToDelegate() {
        RecordingDelegate delegate = new RecordingDelegate();
        LocaleAwareToolCallback decorator =
                new LocaleAwareToolCallback(delegate, "本地化描述");

        ToolContext ctx = new ToolContext(Map.of("k", "v"));
        String out = decorator.call("{\"x\":1}", ctx);

        assertEquals("ok", out);
        assertEquals("{\"x\":1}", delegate.lastInput);
        assertSame(ctx, delegate.lastContext,
                "ToolContext must reach the underlying tool unchanged");
    }

    @Test
    void getToolMetadata_isForwardedSoReturnDirectIsPreserved() {
        ToolMetadata directMetadata = ToolMetadata.builder().returnDirect(true).build();
        RecordingDelegate delegate = new RecordingDelegate();
        delegate.metadata = directMetadata;

        LocaleAwareToolCallback decorator = new LocaleAwareToolCallback(delegate, "本地化描述");
        assertSame(directMetadata, decorator.getToolMetadata(),
                "decorator must not flip returnDirect by inheriting the framework default");
    }

    private static final class RecordingDelegate implements ToolCallback {
        String lastInput;
        ToolContext lastContext;
        ToolMetadata metadata = ToolMetadata.builder().build();

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("recording-tool")
                    .description("...")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return metadata;
        }

        @Override
        public String call(String toolInput) {
            this.lastInput = toolInput;
            this.lastContext = null;
            return "ok";
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            this.lastInput = toolInput;
            this.lastContext = toolContext;
            return "ok";
        }
    }
}
