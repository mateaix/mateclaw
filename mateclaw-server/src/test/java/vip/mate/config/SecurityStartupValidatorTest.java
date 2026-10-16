package vip.mate.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityStartupValidatorTest {

    @ParameterizedTest(name = "rejects {0} JWT secrets")
    @MethodSource("invalidSecrets")
    void rejectsInvalidJwtSecretsAtStartup(String description, String secret) {
        assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(secret));
    }

    @Test
    void acceptsAConfiguredHmacSha256Secret() {
        SecurityStartupValidator validator = validatorWithSecret("0123456789abcdef0123456789abcdef");

        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments(new String[0])));
    }

    private static Stream<Arguments> invalidSecrets() {
        return Stream.of(
                Arguments.of("missing", null),
                Arguments.of("empty", ""),
                Arguments.of("blank", " \t\n"),
                Arguments.of("short", "0123456789abcdef0123456789abcde"),
                Arguments.of("built-in application.yml", "MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production"),
                Arguments.of("built-in AuthService fallback", "MateClaw-Secret-Key-2024-Very-Long-String")
        );
    }

    private static SecurityStartupValidator validatorWithSecret(String secret) {
        SecurityStartupValidator validator = new SecurityStartupValidator(secret);
        ReflectionTestUtils.setField(validator, "corsOrigins", "http://localhost:3000");
        return validator;
    }
}
