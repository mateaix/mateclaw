package vip.mate.tool.image.vision.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;

/**
 * DashScope vision provider — uses {@code qwen-vl-max} via the
 * OpenAI-compatible endpoint at {@code /compatible-mode/v1/chat/completions}.
 *
 * <p>Sits at the front of the auto-detect chain when a DashScope provider row
 * is configured in the admin UI: per-image cost is the lowest of the supported
 * vendors, and DashScope is the most common first provider added on the
 * Chinese cloud rollout. Falls back to the next provider in the chain when no
 * DashScope API key is available.
 */
@Component
public class DashScopeVisionProvider extends OpenAiCompatibleVisionProvider {

    private static final String PROVIDER_ID = "dashscope-vision";
    private static final String DASHSCOPE_PROVIDER_KEY = "dashscope";
    private static final String DEFAULT_MODEL = "qwen-vl-max";
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    public DashScopeVisionProvider(ModelProviderService modelProviderService,
                                    ObjectMapper objectMapper) {
        super(modelProviderService, objectMapper);
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String label() {
        return "DashScope qwen-vl";
    }

    @Override
    public int autoDetectOrder() {
        return 10;
    }

    @Override
    protected String providerKey() {
        return DASHSCOPE_PROVIDER_KEY;
    }

    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    @Override
    protected String defaultModel() {
        return DEFAULT_MODEL;
    }
}
