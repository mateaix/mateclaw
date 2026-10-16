package vip.mate.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AuthServiceJwtSecretTest {

    private static final String ROTATED_SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void rejectsTokensSignedWithThePreviouslyPaddedEmptySecretAfterRotation() {
        AuthService authService = authServiceWithSecret(ROTATED_SECRET);
        String token = forgeAdminToken(new byte[32]);

        assertNull(authService.parseClaims(token));
    }

    @Test
    void rejectsTokensSignedWithThePreviousApplicationDefaultAfterRotation() {
        String previousSecret = "MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production";
        AuthService authService = authServiceWithSecret(ROTATED_SECRET);

        assertNull(authService.parseClaims(forgeAdminToken(previousSecret.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsTokensSignedWithThePreviousServiceFallbackAfterRotation() {
        String previousSecret = "MateClaw-Secret-Key-2024-Very-Long-String";
        AuthService authService = authServiceWithSecret(ROTATED_SECRET);

        assertNull(authService.parseClaims(forgeAdminToken(previousSecret.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void doesNotPadShortSecretsWhenSigningTokens() {
        AuthService authService = authServiceWithSecret("too-short");
        UserEntity user = adminUser();

        assertThrows(IllegalArgumentException.class, () -> authService.generateToken(user));
    }

    @Test
    void signsAndParsesTokensWithAConfiguredSecret() {
        AuthService authService = authServiceWithSecret(ROTATED_SECRET);
        UserEntity user = adminUser();

        var claims = authService.parseClaims(authService.generateToken(user));

        assertNotNull(claims);
        assertEquals("admin", claims.getSubject());
        assertEquals(1L, claims.get("userId", Long.class));
    }

    private static AuthService authServiceWithSecret(String secret) {
        AuthService authService = new AuthService(mock(UserMapper.class), mock(BCryptPasswordEncoder.class));
        ReflectionTestUtils.setField(authService, "jwtSecret", secret);
        ReflectionTestUtils.setField(authService, "jwtExpiration", 86_400_000L);
        return authService;
    }

    private static UserEntity adminUser() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("admin");
        return user;
    }

    private static String forgeAdminToken(byte[] secret) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("admin")
                .claim("userId", 1L)
                .issuedAt(new Date(now))
                .expiration(new Date(now + 86_400_000L))
                .signWith(Keys.hmacShaKeyFor(secret))
                .compact();
    }
}
