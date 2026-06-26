package vip.mate.auth.sso;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.auth.sso.model.SsoStateEntity;
import vip.mate.auth.sso.provider.SsoUserInfo;
import vip.mate.auth.sso.repository.SsoStateMapper;
import vip.mate.exception.MateClawException;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * OAuth2 state / bind_token 签发与校验服务。
 * <p>
 * 落 DB (sso_state 表) 而非内存: 多节点部署下 /authorize 与 /callback 可能落到不同节点。
 * state 和 bind_token 的 jti 共用同一张表, 用 {@code kind} 列区分。
 *
 * <p><b>State</b> (OAuth2 CSRF):
 * <ul>
 *   <li>签发: Base64(nonce + "." + HMAC-SHA256(nonce, jwtSecret)), 存 DB (kind=state)</li>
 *   <li>校验: 验 HMAC 签名 + 5min TTL + 一次性消费 (UPDATE consumed=1 WHERE consumed=0)</li>
 * </ul>
 *
 * <p><b>bind_token</b> (link-only 模式, 自包含 JWT):
 * <ul>
 *   <li>签发: JWT(jti, provider, externalId, ..., exp=10min), 用 jwtSecret 签名</li>
 *   <li>校验: 验签 + 过期 + 单次消费 (jti 写入 sso_state 撞 PK, 只有首个请求成功)</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mateclaw.sso.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SsoStateService {

    private static final int STATE_TTL_SECONDS = 5 * 60;       // 5 min
    private static final int BIND_TOKEN_TTL_SECONDS = 10 * 60; // 10 min
    private static final String KIND_STATE = "state";
    private static final String KIND_BIND = "bind";

    private final SsoStateMapper stateMapper;

    @Value("${mateclaw.jwt.secret:MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production}")
    private String jwtSecret;

    // ==================== State (OAuth2 CSRF) ====================

    /**
     * 签发 OAuth2 state token 并持久化。返回 Base64(nonce.signature) 格式。
     */
    public String issueState(String provider) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = hmacSha256Hex(nonce);
        String state = nonce + "." + signature;

        SsoStateEntity entity = new SsoStateEntity();
        entity.setToken(state);
        entity.setKind(KIND_STATE);
        entity.setProvider(provider);
        entity.setConsumed(0);
        entity.setCreatedAt(LocalDateTime.now());
        stateMapper.insert(entity);

        return state;
    }

    /**
     * 校验 state 签名 + 过期 + 一次性消费。校验失败抛 400。
     */
    public void verifyState(String state) {
        if (state == null || state.isBlank()) {
            throw new MateClawException("err.sso.state_missing", 400, "缺少 state 参数");
        }
        int dot = state.indexOf('.');
        if (dot <= 0 || dot >= state.length() - 1) {
            throw new MateClawException("err.sso.state_invalid", 400, "state 格式无效");
        }
        String nonce = state.substring(0, dot);
        String signature = state.substring(dot + 1);

        // 1. 验 HMAC 签名
        String expected = hmacSha256Hex(nonce);
        if (!expected.equals(signature)) {
            throw new MateClawException("err.sso.state_invalid", 400, "state 签名校验失败");
        }

        // 2. 一次性消费 + TTL: UPDATE consumed=1 WHERE token=? AND consumed=0 AND created_at > cutoff.
        // 加 created_at 条件让 5min TTL 在消费阶段强制生效 —— 否则未消费的 state
        // 只在 1h purge 后才物理删除, /authorize 后 30min 的 /callback 仍能通过。
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(STATE_TTL_SECONDS);
        int rows = stateMapper.update(null, new LambdaUpdateWrapper<SsoStateEntity>()
                .eq(SsoStateEntity::getToken, state)
                .eq(SsoStateEntity::getConsumed, 0)
                .gt(SsoStateEntity::getCreatedAt, cutoff)
                .set(SsoStateEntity::getConsumed, 1));
        if (rows == 0) {
            throw new MateClawException("err.sso.state_expired_or_used",
                    400, "state 已过期或已被使用, 请重新登录");
        }
    }

    // ==================== bind_token (link-only 模式) ====================

    /**
     * 签发 bind_token (自包含 JWT), 携带 IdP 用户信息。TTL 10min。
     */
    public String issueBindToken(String provider, SsoUserInfo info) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())    // jti
                .claim("provider", provider)
                .claim("externalId", info.externalId())
                .claim("unionId", info.unionId())
                .claim("externalName", info.displayName())
                .issuedAt(new Date(now))
                .expiration(new Date(now + BIND_TOKEN_TTL_SECONDS * 1000L))
                .signWith(getSignKey())
                .compact();
    }

    /**
     * 校验 bind_token 验签 + 过期 + 单次消费 (jti 撞 PK)。返回 claims 供绑定使用。
     */
    public BindTokenClaims verifyBindToken(String bindToken) {
        if (bindToken == null || bindToken.isBlank()) {
            throw new MateClawException("err.sso.bind_token_missing", 400, "缺少 bind_token");
        }
        // 1. 验签 + 过期
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(getSignKey())
                    .build()
                    .parseSignedClaims(bindToken)
                    .getPayload();
        } catch (Exception e) {
            throw new MateClawException("err.sso.bind_token_invalid",
                    400, "bind_token 无效或已过期");
        }

        String jti = claims.getId();
        if (jti == null) {
            throw new MateClawException("err.sso.bind_token_invalid", 400, "bind_token 缺少 jti");
        }

        // 2. 单次消费: INSERT (token=jti, kind=bind) 撞 PK, 只有首个请求成功
        SsoStateEntity consumed = new SsoStateEntity();
        consumed.setToken(jti);
        consumed.setKind(KIND_BIND);
        consumed.setProvider(claims.get("provider", String.class));
        consumed.setConsumed(1);
        consumed.setCreatedAt(LocalDateTime.now());
        try {
            stateMapper.insert(consumed);
        } catch (DuplicateKeyException e) {
            throw new MateClawException("err.sso.bind_token_used",
                    400, "bind_token 已被使用, 请重新登录");
        }

        return new BindTokenClaims(
                claims.get("provider", String.class),
                claims.get("externalId", String.class),
                claims.get("unionId", String.class),
                claims.get("externalName", String.class));
    }

    /** bind_token 校验通过后返回的 claims。 */
    public record BindTokenClaims(String provider, String externalId,
                                   String unionId, String externalName) {}

    // ==================== 过期清理 (ShedLock 定时任务) ====================

    /**
     * 每小时清理过期 state/bind_token 行。
     * 走 LambdaQuery + Java 时间过滤, 通吃三方言 (不用 NOW() - INTERVAL SQL 方言)。
     */
    @Scheduled(fixedDelay = 3600_000) // 1h
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minus(1, ChronoUnit.HOURS);
        int deleted = stateMapper.delete(new LambdaQueryWrapper<SsoStateEntity>()
                .lt(SsoStateEntity::getCreatedAt, cutoff));
        if (deleted > 0) {
            log.info("[SsoState] Purged {} expired state/bind rows (cutoff={})", deleted, cutoff);
        }
    }

    // ==================== helpers ====================

    private SecretKey getSignKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        // HMAC-SHA 需要 >= 256 bit (32 byte); 短 secret 用 0x00 填充到 32 byte (与 AuthService 一致)
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private String hmacSha256Hex(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(getSignKey());
            byte[] hash = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }
}
