package vip.mate.plugin.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Plugin info DTO for REST API responses.
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class PluginInfo {

    private String name;
    private String version;
    private String type;
    private String displayName;
    private String description;
    private String author;
    private boolean enabled;
    private String status;
    private String errorMessage;
    private String jarPath;

    /** Names of tools registered by this plugin */
    private List<String> registeredTools;

    /** Channel types registered by this plugin */
    private List<String> registeredChannels;

    /** Provider ID registered by this plugin (null if none) */
    private String registeredProvider;

    /** Memory provider ID registered by this plugin (null if none) */
    private String registeredMemoryProvider;

    /** Search provider ids registered by this plugin */
    private List<String> registeredSearchProviders;

    /** Plugin config schema (from manifest) */
    private Map<String, Object> configSchema;

    /** Current config values */
    private Map<String, Object> currentConfig;
}
