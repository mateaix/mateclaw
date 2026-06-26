package vip.mate.auth.sso;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.auth.service.AuthService;
import vip.mate.auth.sso.model.ExternalIdentityEntity;
import vip.mate.auth.sso.provider.SsoProvider;
import vip.mate.auth.sso.provider.SsoProviderRegistry;
import vip.mate.auth.sso.provider.SsoUserInfo;
import vip.mate.auth.sso.repository.ExternalIdentityMapper;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SSO 核心业务逻辑: 授权 URL 构造、回调用户映射、账号绑定。
 * <p>
 * 用户映射策略 (两者结合):
 * <ul>
 *   <li>已绑定 → 更新 last_login + external 信息 → 签发 JWT</li>
 *   <li>未绑定 + link-only → 签发 bind_token, 前端引导绑定</li>
 *   <li>未绑定 + 默认 → 自动创建 mate_user + external_identity</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoService {

    private final SsoProviderRegistry registry;
    private final SsoStateService stateService;
    private final ExternalIdentityMapper identityMapper;
    private final UserMapper userMapper;
    private final AuthService authService;
    private final SsoProperties ssoProperties;
    private final BCryptPasswordEncoder passwordEncoder;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    /** Optional — audit may be null in narrow test contexts. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditEventService auditService;

    // ==================== authorize ====================

    /**
     * 构造授权 URL + 签发 state。
     *
     * @return { authorizeUrl, state }
     */
    public Map<String, String> handleAuthorize(String providerId) {
        SsoProvider provider = registry.get(providerId)
                .orElseThrow(() -> new MateClawException("err.sso.unknown_provider",
                        400, "未知的 SSO provider: " + providerId));
        String state = stateService.issueState(providerId);
        String url = provider.authorizeUrl(state);
        return Map.of("authorizeUrl", url, "state", state);
    }

    // ==================== callback ====================

    /**
     * OAuth2 回调: code → JWT。
     * <p>
     * 已绑定用户直接签发 JWT; 未绑定用户根据 link-only 策略决定行为:
     * <ul>
     *   <li>link-only → 返回 {@link SsoCallbackResponse#bindRequired} 携带 bind_token</li>
     *   <li>默认 → 自动创建 mate_user (含并发幂等保护)</li>
     * </ul>
     */
    public SsoCallbackResponse handleCallback(String providerId, String code, String state) {
        // 1. 校验 state (签名 + 过期 + 一次性消费)
        stateService.verifyState(state);

        // 2. code → IdP 用户信息
        SsoProvider provider = registry.get(providerId)
                .orElseThrow(() -> new MateClawException("err.sso.unknown_provider",
                        400, "未知的 SSO provider: " + providerId));
        SsoUserInfo info = provider.resolve(code, state);

        // 3. 查已有绑定
        ExternalIdentityEntity identity = findIdentity(providerId, info);
        if (identity != null) {
            UserEntity user = userMapper.selectById(identity.getUserId());
            if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
                throw new MateClawException("err.sso.account_disabled",
                        403, "账号已停用或不存在");
            }
            updateIdentityOnLogin(identity, info);
            audit("sso.login", providerId, info.externalId(), user.getId());
            return SsoCallbackResponse.of(loginSuccess(user));
        }

        // 4. 未绑定
        if (ssoProperties.isLinkOnly()) {
            String bindToken = stateService.issueBindToken(providerId, info);
            audit("sso.bind_required", providerId, info.externalId(), null);
            return SsoCallbackResponse.bindRequired(bindToken, providerId, info.displayName());
        }

        // 5. 默认: 自动创建 (含并发幂等, 最多重试一次)
        UserEntity newUser = createSsoUser(providerId, info, false);
        return SsoCallbackResponse.of(loginSuccess(newUser));
    }

    // ==================== bind (link-only 模式) ====================

    /**
     * 绑定 SSO 身份到已有 mate_user 账号。
     * 校验 bind_token + 用户名密码 → 创建 external_identity → 签发 JWT。
     */
    public LoginResponse handleBind(String bindToken, String username, String password) {
        SsoStateService.BindTokenClaims claims = stateService.verifyBindToken(bindToken);

        // 校验用户名密码 (与 AuthService.login 一致的 BCrypt 校验)
        UserEntity user = authService.findByUsername(username);
        if (user == null || user.getPassword() == null
                || !passwordEncoder.matches(password, user.getPassword())) {
            throw new MateClawException("err.auth.invalid_credentials",
                    401, "用户名或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new MateClawException("err.sso.account_disabled",
                    403, "账号已停用");
        }

        // 创建绑定 (并发幂等: UNIQUE(provider, external_id) 兜底)
        try {
            ExternalIdentityEntity identity = new ExternalIdentityEntity();
            identity.setUserId(user.getId());
            identity.setProvider(claims.provider());
            identity.setExternalId(claims.externalId());
            identity.setUnionId(claims.unionId());
            identity.setExternalName(claims.externalName());
            identity.setLastLoginAt(LocalDateTime.now());
            identityMapper.insert(identity);
        } catch (DuplicateKeyException e) {
            throw new MateClawException("err.sso.already_bound",
                    409, "该飞书账号已绑定到其他用户");
        }

        audit("sso.bind", claims.provider(), claims.externalId(), user.getId());
        return loginSuccess(user);
    }

    /**
     * 查找已绑定的外部身份。union_id 优先, 回退 external_id。
     */
    private ExternalIdentityEntity findIdentity(String providerId, SsoUserInfo info) {
        // 优先 union_id
        if (info.unionId() != null && !info.unionId().isBlank()) {
            ExternalIdentityEntity byUnion = identityMapper.selectOne(
                    new LambdaQueryWrapper<ExternalIdentityEntity>()
                            .eq(ExternalIdentityEntity::getProvider, providerId)
                            .eq(ExternalIdentityEntity::getUnionId, info.unionId()));
            if (byUnion != null) return byUnion;
        }
        // 回退 external_id
        return identityMapper.selectOne(
                new LambdaQueryWrapper<ExternalIdentityEntity>()
                        .eq(ExternalIdentityEntity::getProvider, providerId)
                        .eq(ExternalIdentityEntity::getExternalId, info.externalId()));
    }

    /**
     * 更新绑定记录的 last_login + external 信息。
     */
    private void updateIdentityOnLogin(ExternalIdentityEntity identity, SsoUserInfo info) {
        identityMapper.update(null, new LambdaUpdateWrapper<ExternalIdentityEntity>()
                .eq(ExternalIdentityEntity::getId, identity.getId())
                .set(ExternalIdentityEntity::getLastLoginAt, LocalDateTime.now())
                .set(ExternalIdentityEntity::getExternalName, info.displayName())
                .set(ExternalIdentityEntity::getExternalAvatar, info.avatarUrl())
                .set(ExternalIdentityEntity::getExternalEmail, info.email()));
    }

    /**
     * 自动创建 SSO 用户 (含并发幂等: catch DuplicateKeyException → 回滚孤儿 user → 重查)。
     *
     * @param retry 是否已重试过一次。第二次仍撞 PK 时直接抛异常 (不再递归, 避免栈溢出)。
     */
    private UserEntity createSsoUser(String providerId, SsoUserInfo info, boolean retry) {
        UserEntity newUser = new UserEntity();
        newUser.setUsername(providerId + "_" + info.externalId()); // feishu_<full open_id>
        newUser.setPassword(null); // 仅 SSO 登录
        newUser.setNickname(info.displayName());
        newUser.setAvatar(info.avatarUrl());
        newUser.setEmail(info.email());
        newUser.setRole(ssoProperties.getDefaultRole());
        newUser.setEnabled(true);

        try {
            userMapper.insert(newUser);
            ExternalIdentityEntity identity = new ExternalIdentityEntity();
            identity.setUserId(newUser.getId());
            identity.setProvider(providerId);
            identity.setExternalId(info.externalId());
            identity.setUnionId(info.unionId());
            identity.setExternalName(info.displayName());
            identity.setExternalAvatar(info.avatarUrl());
            identity.setExternalEmail(info.email());
            identity.setLastLoginAt(LocalDateTime.now());
            identityMapper.insert(identity);
            audit("sso.auto_create", providerId, info.externalId(), newUser.getId());
            return newUser;
        } catch (DuplicateKeyException e) {
            // 并发: 另一个请求已创建了该用户。回滚刚建的孤儿 user, 重查已存在的 identity。
            log.info("[SSO] Concurrent auto-create for provider={}, externalId={}: "
                    + "rolling back orphan user {}, falling back to existing", providerId, info.externalId(), newUser.getId());
            userMapper.deleteById(newUser.getId()); // mate_user 无 @TableLogic, 物理删
            ExternalIdentityEntity existing = findIdentity(providerId, info);
            if (existing != null) {
                return userMapper.selectById(existing.getUserId());
            }
            // 极端竞态: identity 也被并发删了。重试一次, 不再递归。
            if (retry) {
                throw new MateClawException("err.sso.concurrent_create_failed",
                        503, "SSO 登录遇到并发冲突, 请重试");
            }
            return createSsoUser(providerId, info, true);
        }
    }

    private LoginResponse loginSuccess(UserEntity user) {
        String token = authService.generateToken(user);
        return new LoginResponse(user.getId(), token, user.getUsername(),
                user.getNickname(), user.getRole());
    }

    private void audit(String action, String provider, String externalId, Long userId) {
        if (auditService != null) {
            try {
                String detail = objectMapper.writeValueAsString(java.util.Map.of(
                        "provider", provider != null ? provider : "",
                        "userId", userId != null ? userId : "null"));
                auditService.record(action, "sso",
                        provider + ":" + externalId, externalId, detail);
            } catch (Exception e) {
                log.debug("[SSO] audit write failed for {}: {}", action, e.getMessage());
            }
        }
    }
}
