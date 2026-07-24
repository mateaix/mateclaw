package vip.mate.plugin.mem0;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Mem0ConfigTest {

    @Test
    void isUsable_false_whenBaseUrlNull() {
        Mem0Config c = new Mem0Config(null, null, true, true, 5, 1000);
        assertThat(c.isUsable()).isFalse();
    }

    @Test
    void isUsable_false_whenBaseUrlBlank() {
        Mem0Config c = new Mem0Config("   ", null, true, true, 5, 1000);
        assertThat(c.isUsable()).isFalse();
    }

    @Test
    void isUsable_true_whenBaseUrlSet() {
        Mem0Config c = new Mem0Config("http://localhost:8080", null, true, true, 5, 1000);
        assertThat(c.isUsable()).isTrue();
    }

    @Test
    void normalizedBaseUrl_stripsTrailingSlashes() {
        Mem0Config c = new Mem0Config("http://localhost:8080///", null, true, true, 5, 1000);
        assertThat(c.normalizedBaseUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    void normalizedBaseUrl_keepsUrlWithoutTrailingSlash() {
        Mem0Config c = new Mem0Config("http://localhost:8080", null, true, true, 5, 1000);
        assertThat(c.normalizedBaseUrl()).isEqualTo("http://localhost:8080");
    }
}
