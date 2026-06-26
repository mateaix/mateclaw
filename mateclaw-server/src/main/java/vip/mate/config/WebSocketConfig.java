package vip.mate.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import vip.mate.channel.web.TalkModeWebSocketHandler;
import vip.mate.tool.local.DesktopBridgeHandshakeInterceptor;
import vip.mate.tool.local.DesktopBridgeWebSocketHandler;

/**
 * WebSocket 配置
 * <p>
 * 注册 Talk Mode WebSocket 端点。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * Max binary frame the TalkMode WebSocket accepts. Recordings come over
     * as 16 kHz / 16-bit / mono PCM WAV — roughly 32 KB per second of audio.
     * 8 MB headroom covers ~4 minutes of speech, which is more than any
     * realistic single-turn voice message. Default is 8 KB which used to
     * crash the connection (CloseStatus 1009 "Message too big") on every
     * non-trivial recording.
     */
    private static final int MAX_BINARY_BUFFER_BYTES = 8 * 1024 * 1024;

    /** Text frames stay reasonably small (init / state / transcript JSON). */
    private static final int MAX_TEXT_BUFFER_BYTES = 64 * 1024;

    private final TalkModeWebSocketHandler talkModeHandler;
    private final DesktopBridgeWebSocketHandler desktopBridgeHandler;
    private final DesktopBridgeHandshakeInterceptor desktopBridgeHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(talkModeHandler, "/api/v1/talk/ws")
                .setAllowedOrigins("*");
        // Desktop local-tool tunnel. The handshake interceptor authenticates the
        // ?token= query param and pins the username into the session attributes;
        // an unauthenticated socket never reaches the handler.
        registry.addHandler(desktopBridgeHandler, "/api/v1/desktop/ws")
                .addInterceptors(desktopBridgeHandshakeInterceptor)
                .setAllowedOrigins("*");
    }

    /**
     * Tomcat-level buffer override. Without this Spring's
     * {@link org.springframework.web.socket.handler.AbstractWebSocketHandler}
     * still buffers the whole binary message before dispatching to the handler
     * — capped at 8 KB by default — and a single voice clip blows past that
     * limit on the very first send. Bumping the binary buffer is simpler than
     * implementing partial-message handling, given the bounded size of
     * speech-to-text clips. See discussion in the V46 STT bug-fix series.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAX_BINARY_BUFFER_BYTES);
        container.setMaxTextMessageBufferSize(MAX_TEXT_BUFFER_BYTES);
        return container;
    }
}
