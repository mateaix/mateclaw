package vip.mate.channel.qrcode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.qq.QQAppRegistrationService;
import vip.mate.channel.qrcode.util.QrCodeImageEncoder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QR-code auth provider for the QQ Bot scan-to-bind flow.
 *
 * <p>Wraps {@link QQAppRegistrationService} to expose its session model
 * through the unified {@link ChannelQRCodeAuthProvider} contract. On
 * {@code status=confirmed}, the credentials surface as {@code app_id} and
 * {@code client_secret} — matching the keys the QQ channel adapter reads
 * from {@code configJson}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QQQRCodeAuthProvider implements ChannelQRCodeAuthProvider {

    private final QQAppRegistrationService service;

    @Override
    public String channelType() {
        return "qq";
    }

    @Override
    public Map<String, Object> begin(Map<String, String> params) throws Exception {
        QQAppRegistrationService.RegistrationSession session = service.begin();
        return Map.of("session_id", session.sessionId);
    }

    @Override
    public Map<String, Object> pollStatus(String sessionId) {
        QQAppRegistrationService.RegistrationSession session = service.getSession(sessionId);
        if (session == null) {
            return Map.of("status", "expired", "error", "session not found or expired");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", session.status.name().toLowerCase());
        if (session.qrcodeUrl != null) {
            body.put("qrcode_url", session.qrcodeUrl);
            if (session.qrcodeImgDataUri == null) {
                try {
                    session.qrcodeImgDataUri = QrCodeImageEncoder.toDataUri(session.qrcodeUrl);
                } catch (Exception e) {
                    log.warn("[qq-register] QR encode failed: {}", e.getMessage());
                }
            }
            if (session.qrcodeImgDataUri != null) {
                body.put("qrcode_img", session.qrcodeImgDataUri);
            }
        }
        if (session.status == QQAppRegistrationService.Status.CONFIRMED) {
            // Key names mirror configJson fields that QQChannelAdapter reads.
            body.put("app_id", session.clientId);
            body.put("client_secret", session.clientSecret);
            if (session.userOpenid != null) {
                body.put("user_openid", session.userOpenid);
            }
        }
        if (session.errorMessage != null) {
            body.put("error", session.errorMessage);
        }
        return body;
    }
}
