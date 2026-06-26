package vip.mate.auth.sso.provider;

/**
 * SSO 身份提供方抽象。每个 IdP（飞书/钉钉/企微/...）实现此接口。
 * <p>
 * 注册到 {@link SsoProviderRegistry} 后由 {@code SsoController} 按 id 路由。
 *
 * @author MateClaw Team
 */
public interface SsoProvider {

    /** Provider 标识, 如 "feishu" */
    String id();

    /** 展示名, 如 "飞书" (前端渲染按钮用) */
    String displayName();

    /**
     * 构造授权 URL。前端 window.location 跳转到此 URL 让用户授权。
     *
     * @param state CSRF 防护 token, 原样附加到授权 URL 的 state 参数
     * @return 完整的 IdP 授权 URL
     */
    String authorizeUrl(String state);

    /**
     * 用授权码换取用户信息。
     *
     * @param code  IdP 回调带回的授权码
     * @param state 回调带回的 state（已由 Controller 校验过签名 + 一次性消费）
     * @return IdP 侧的标准化用户身份信息
     */
    SsoUserInfo resolve(String code, String state);
}
