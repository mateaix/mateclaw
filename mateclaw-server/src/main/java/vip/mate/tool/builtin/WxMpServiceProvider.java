package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import org.springframework.stereotype.Component;
import vip.mate.system.service.SystemSettingService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shares one {@link WxMpService} per {@code appId} and persists its
 * {@code access_token} across restarts.
 *
 * <p>Why this exists: WeChat allows only ONE valid {@code access_token} per
 * appId at a time and rate-limits token fetches — fetching a new one silently
 * invalidates the previous. Building a fresh {@code WxMpServiceImpl} on every
 * call (the old {@code GzhPublishTool} behaviour) meant every publish, and every
 * process restart, re-fetched a token and could thrash a token shared with other
 * callers. Here the service (and its in-memory token) is cached by appId, and the
 * token is mirrored into system settings so a restart reuses the live token
 * instead of fetching another. Changing the app secret transparently rebuilds the
 * cached service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxMpServiceProvider {

    private final SystemSettingService settingService;
    private final ConcurrentMap<String, Holder> cache = new ConcurrentHashMap<>();

    private record Holder(String secret, WxMpService service) {}

    /** Get (or build) the shared service for this appId/secret pair. */
    public WxMpService getService(String appId, String appSecret) {
        Holder existing = cache.get(appId);
        if (existing != null && existing.secret().equals(appSecret)) {
            return existing.service();
        }
        WxMpService service = build(appId, appSecret);
        cache.put(appId, new Holder(appSecret, service));
        return service;
    }

    /** Drop the cached service for an appId (e.g. after a credential change). */
    public void invalidate(String appId) {
        cache.remove(appId);
    }

    private WxMpService build(String appId, String appSecret) {
        DbTokenConfig config = new DbTokenConfig(appId, settingService);
        config.setAppId(appId);
        config.setSecret(appSecret);
        config.loadPersistedToken();
        WxMpService service = new WxMpServiceImpl();
        service.setWxMpConfigStorage(config);
        log.debug("[WxMpServiceProvider] built WxMpService for appId={}", appId);
        return service;
    }

    /**
     * Config storage that mirrors the access_token into system settings so it
     * survives a JVM restart. Token keys are per-appId and short-lived, so they
     * are stored as ordinary (non-encrypted) settings.
     */
    static final class DbTokenConfig extends WxMpDefaultConfigImpl {

        private final String appId;
        private final transient SystemSettingService settings;

        DbTokenConfig(String appId, SystemSettingService settings) {
            this.appId = appId;
            this.settings = settings;
        }

        private String tokenKey() {
            return "weixinoa.token." + appId;
        }

        private String expiresKey() {
            return "weixinoa.token_expires." + appId;
        }

        /** Seed the in-memory token from a previously persisted, still-valid one. */
        void loadPersistedToken() {
            String token = settings.getString(tokenKey(), "");
            String expires = settings.getString(expiresKey(), "");
            if (token == null || token.isBlank() || expires == null || expires.isBlank()) {
                return;
            }
            try {
                long expiresAt = Long.parseLong(expires.trim());
                if (expiresAt > System.currentTimeMillis()) {
                    setAccessToken(token);
                    setExpiresTime(expiresAt);
                }
            } catch (NumberFormatException ignore) {
                // Corrupt persisted expiry — ignore and let the service fetch fresh.
            }
        }

        @Override
        public void updateAccessToken(String accessToken, int expiresInSeconds) {
            super.updateAccessToken(accessToken, expiresInSeconds);
            // Mirror the freshly minted token so a restart reuses it.
            try {
                settings.saveString(tokenKey(), accessToken, "WeChat OA access_token cache");
                settings.saveString(expiresKey(), String.valueOf(getExpiresTime()),
                        "WeChat OA access_token expiry (epoch ms)");
            } catch (Exception e) {
                // Persistence is best-effort; the in-memory token still works this run.
                log.debug("[WxMpServiceProvider] could not persist access_token for {}: {}", appId, e.toString());
            }
        }
    }
}
