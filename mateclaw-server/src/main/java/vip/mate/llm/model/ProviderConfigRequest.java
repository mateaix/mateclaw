package vip.mate.llm.model;

import lombok.Data;

import java.util.Map;

@Data
public class ProviderConfigRequest {
    private String apiKey;
    private String baseUrl;
    private String protocol;
    private String chatModel;
    private Map<String, Object> generateKwargs;
    private Boolean requireApiKey;
    /**
     * RFC-009 P3.5: provider's position in the multi-model failover chain.
     * {@code 0} = excluded; positive ints define ascending try-order. When
     * {@code null} the field is left untouched on update.
     */
    private Integer fallbackPriority;
}
