package vip.mate.auth.sso.provider;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSO Provider 注册表。按 id 查找 Provider, 列出已启用的 Provider 供前端渲染按钮。
 * <p>
 * Provider 通过构造函数注入 (Spring 按 {@code @ConditionalOnProperty} 按需实例化)。
 *
 * @author MateClaw Team
 */
@Component
public class SsoProviderRegistry {

    private final Map<String, SsoProvider> providers = new LinkedHashMap<>();

    /**
     * Spring 注入所有已启用的 {@link SsoProvider} bean。当 SSO 未启用时该列表为空。
     */
    public SsoProviderRegistry(List<SsoProvider> providerBeans) {
        if (providerBeans != null) {
            for (SsoProvider p : providerBeans) {
                providers.put(p.id(), p);
            }
        }
    }

    /** 按 id 查 */
    public Optional<SsoProvider> get(String providerId) {
        if (providerId == null) return Optional.empty();
        return Optional.ofNullable(providers.get(providerId));
    }

    /** 列出所有已启用的 Provider (供前端渲染 SSO 按钮) */
    public List<SsoProvider> listEnabled() {
        return List.copyOf(providers.values());
    }

    /** 是否有任何 Provider 已启用 */
    public boolean hasEnabled() {
        return !providers.isEmpty();
    }
}
