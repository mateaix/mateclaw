package vip.mate.auth.sso;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSO 单点登录配置。
 * <p>
 * 全局开关默认关闭, 不影响未启用 SSO 的现有部署。
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.sso")
public class SsoProperties {

    /** 是否启用 SSO (全局开关) */
    private boolean enabled = false;

    /**
     * 「仅允许绑定已有账号」模式。
     * {@code false} = 未绑定时自动创建 mate_user; {@code true} = 要求绑定已存在的账号。
     */
    private boolean linkOnly = false;

    /** 新建 SSO 用户的默认角色 */
    private String defaultRole = "user";

    /** 飞书 Provider 配置 */
    private Feishu feishu = new Feishu();

    @Data
    public static class Feishu {
        /** 是否启用飞书 SSO */
        private boolean enabled = false;
        /** 飞书应用 App ID */
        private String appId;
        /** 飞书应用 App Secret */
        private String appSecret;
        /**
         * 国际版切换: {@code feishu} (国内) / {@code lark} (国际版 Lark)。
         * 决定 apiBase: {@code https://open.feishu.cn} / {@code https://open.larksuite.com}
         */
        private String domain = "feishu";
        /**
         * SSO 回调地址, 通常 {@code https://your-domain/login?sso=callback}。
         * 飞书授权后带 code 回跳到此地址。
         */
        private String redirectUri;
    }
}
