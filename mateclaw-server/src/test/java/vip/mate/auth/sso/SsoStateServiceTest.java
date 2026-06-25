package vip.mate.auth.sso;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.auth.sso.model.SsoStateEntity;
import vip.mate.auth.sso.provider.SsoUserInfo;
import vip.mate.auth.sso.repository.SsoStateMapper;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the SsoStateService state machine: state issue/verify/one-time-consume
 * and bind_token issue/verify/anti-replay. These are the security-critical paths
 * (CSRF + anti-replay) that the OAuth2 flow depends on.
 */
@ExtendWith(MockitoExtension.class)
class SsoStateServiceTest {

    private static final String SECRET = "test-secret-0123456789-test-secret-01";

    @Mock private SsoStateMapper stateMapper;

    private SsoStateService service;

    @BeforeAll
    static void initMyBatisCache() {
        // LambdaUpdateWrapper needs the entity's TableInfo in MyBatis-Plus's static cache.
        // In a Spring context this happens during mapper scan; in a plain MockitoExtension
        // test we trigger it manually.
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SsoStateEntity.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        service = new SsoStateService(stateMapper, new ObjectMapper());
        // jwtSecret is @Value-injected; set it via reflection since there's no Spring context.
        Field f = SsoStateService.class.getDeclaredField("jwtSecret");
        f.setAccessible(true);
        f.set(service, SECRET);
    }

    // ---------------- state (OAuth2 CSRF) ----------------

    @Test
    @DisplayName("issueState persists a row and returns nonce.signature format")
    void issueStatePersistsAndReturnsSignedFormat() {
        String state = service.issueState("feishu");

        assertThat(state).contains(".");
        String[] parts = state.split("\\.", 2);
        assertThat(parts[0]).isNotBlank();  // nonce
        assertThat(parts[1]).hasSize(64);   // HMAC-SHA256 hex

        verify(stateMapper, times(1)).insert(any(SsoStateEntity.class));
        ArgumentCaptor<SsoStateEntity> captor = ArgumentCaptor.forClass(SsoStateEntity.class);
        verify(stateMapper).insert(captor.capture());
        assertThat(captor.getValue().getToken()).isEqualTo(state);
        assertThat(captor.getValue().getKind()).isEqualTo("state");
        assertThat(captor.getValue().getConsumed()).isEqualTo(0);
    }

    @Test
    @DisplayName("verifyState succeeds when UPDATE affects 1 row (first consumer)")
    void verifyStateSucceedsOnFirstConsume() {
        when(stateMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        String state = service.issueState("feishu");
        service.verifyState(state); // should not throw

        verify(stateMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("verifyState rejects replay when UPDATE affects 0 rows (already consumed)")
    void verifyStateRejectsReplay() {
        // Simulate: state already consumed by another request
        when(stateMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        String state = service.issueState("feishu");
        assertThatThrownBy(() -> service.verifyState(state))
                .hasMessageContaining("已过期或已被使用");
    }

    @Test
    @DisplayName("verifyState rejects tampered signature")
    void verifyStateRejectsTamperedSignature() {
        service.issueState("feishu");
        // Tamper: valid nonce + wrong signature
        String tampered = "abcdef0123456789." + "0".repeat(64);
        assertThatThrownBy(() -> service.verifyState(tampered))
                .hasMessageContaining("签名校验失败");
        // No DB write attempted (fails at signature check before UPDATE)
        verify(stateMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("verifyState rejects malformed state (no dot)")
    void verifyStateRejectsMalformed() {
        assertThatThrownBy(() -> service.verifyState("nodothere"))
                .hasMessageContaining("格式无效");
    }

    @Test
    @DisplayName("verifyState rejects null/blank")
    void verifyStateRejectsNull() {
        assertThatThrownBy(() -> service.verifyState(null))
                .hasMessageContaining("缺少 state");
    }

    // ---------------- bind_token (link-only anti-replay) ----------------

    @Test
    @DisplayName("issueBindToken returns a signed JWT with provider + externalId claims")
    void issueBindTokenContainsClaims() {
        SsoUserInfo info = new SsoUserInfo("ou_123", "on_union", "张三", null, null, null);
        String token = service.issueBindToken("feishu", info);

        assertThat(token).isNotBlank();
        // JWT format: header.payload.signature (3 base64 parts separated by dots)
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("verifyBindToken succeeds on first consume, inserts jti into sso_state")
    void verifyBindTokenSucceedsOnFirstConsume() {
        SsoUserInfo info = new SsoUserInfo("ou_456", null, "李四", null, null, null);
        String token = service.issueBindToken("feishu", info);

        SsoStateService.BindTokenClaims claims = service.verifyBindToken(token);

        assertThat(claims.provider()).isEqualTo("feishu");
        assertThat(claims.externalId()).isEqualTo("ou_456");
        assertThat(claims.externalName()).isEqualTo("李四");

        verify(stateMapper, times(1)).insert(any(SsoStateEntity.class));
        ArgumentCaptor<SsoStateEntity> captor = ArgumentCaptor.forClass(SsoStateEntity.class);
        verify(stateMapper).insert(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo("bind");
        assertThat(captor.getValue().getConsumed()).isEqualTo(1);
    }

    @Test
    @DisplayName("verifyBindToken rejects replay when jti already exists (DuplicateKeyException)")
    void verifyBindTokenRejectsReplay() {
        SsoUserInfo info = new SsoUserInfo("ou_789", null, "王五", null, null, null);
        String token = service.issueBindToken("feishu", info);

        // First insert succeeds; second insert (replay) throws DuplicateKeyException
        when(stateMapper.insert(any(SsoStateEntity.class)))
                .thenReturn(1)
                .thenThrow(new org.springframework.dao.DuplicateKeyException("PK violation"));

        // First consume: success
        service.verifyBindToken(token);
        // Second consume: rejected
        assertThatThrownBy(() -> service.verifyBindToken(token))
                .hasMessageContaining("已被使用");
    }

    @Test
    @DisplayName("verifyBindToken rejects garbage token")
    void verifyBindTokenRejectsGarbage() {
        assertThatThrownBy(() -> service.verifyBindToken("not-a-jwt"))
                .hasMessageContaining("无效或已过期");
    }

    @Test
    @DisplayName("verifyBindToken rejects null/blank")
    void verifyBindTokenRejectsNull() {
        assertThatThrownBy(() -> service.verifyBindToken(null))
                .hasMessageContaining("缺少 bind_token");
    }
}
