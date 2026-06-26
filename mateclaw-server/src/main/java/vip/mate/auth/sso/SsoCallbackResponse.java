package vip.mate.auth.sso;

import lombok.AllArgsConstructor;
import lombok.Data;
import vip.mate.auth.model.LoginResponse;

/**
 * SSO 回调响应。两种互斥形态由 {@code bindRequired} 区分:
 * <ul>
 *   <li>{@code bindRequired=false}: 登录成功, {@code loginResponse} 携带 JWT</li>
 *   <li>{@code bindRequired=true}: link-only 模式未绑定, {@code bindToken} 供前端引导绑定</li>
 * </ul>
 *
 * <p>替代了原先用 {@code R.fail(200, Map.toString())} 传递绑定信号的 hack。
 *
 * @author MateClaw Team
 */
@Data
@AllArgsConstructor
public class SsoCallbackResponse {

    /** link-only 模式下未绑定时为 true */
    private boolean bindRequired;

    /** 登录成功时非空 */
    private LoginResponse loginResponse;

    /** bindRequired=true 时非空, 供前端调 /sso/bind */
    private String bindToken;

    private String provider;
    private String displayName;

    /** 登录成功响应工厂 */
    public static SsoCallbackResponse of(LoginResponse loginResponse) {
        return new SsoCallbackResponse(false, loginResponse, null, null, null);
    }

    /** 需绑定响应工厂 (link-only 模式) */
    public static SsoCallbackResponse bindRequired(String bindToken, String provider, String displayName) {
        return new SsoCallbackResponse(true, null, bindToken, provider, displayName);
    }
}
