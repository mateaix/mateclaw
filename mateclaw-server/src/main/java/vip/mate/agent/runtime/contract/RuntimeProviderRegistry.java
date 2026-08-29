package vip.mate.agent.runtime.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RuntimeProviderRegistry {
    public static final String DEFAULT_RUNTIME = "native";

    private final Map<String, AgentRuntimeProvider> providers;

    public RuntimeProviderRegistry(List<AgentRuntimeProvider> providers) {
        Map<String, AgentRuntimeProvider> registered = new LinkedHashMap<>();
        for (AgentRuntimeProvider provider : providers == null ? List.<AgentRuntimeProvider>of() : providers) {
            if (provider == null || provider.type() == null || provider.type().isBlank()) {
                throw new IllegalArgumentException("runtime provider type is required");
            }
            String type = normalize(provider.type());
            if (registered.putIfAbsent(type, provider) != null) {
                throw new IllegalArgumentException("duplicate runtime provider: " + type);
            }
        }
        this.providers = Map.copyOf(registered);
    }

    public AgentRuntimeProvider resolve(String requestedType) {
        String type = requestedType == null || requestedType.isBlank()
                ? DEFAULT_RUNTIME
                : normalize(requestedType);
        AgentRuntimeProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("unknown runtime provider: " + type);
        }
        return provider;
    }

    private static String normalize(String type) {
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
