package vip.mate.llm.model;

import lombok.Data;

import java.util.List;

@Data
public class CreateCustomProviderRequest {
    private String id;
    private String name;
    private String defaultBaseUrl;
    private String apiKeyPrefix;
    private String protocol;
    private String chatModel;
    private Boolean requireApiKey;
    private List<ModelInfoDTO> models;
}
