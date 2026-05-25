package vip.mate.llm.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.llm.model.ModelProviderEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #81: row-based required-fields decision. Replaces the v1 protocol-keyed
 * lookup, which couldn't tell OpenAI cloud (needs api_key) apart from llama.cpp
 * local (needs base_url) because both ride the OPENAI_COMPATIBLE protocol enum.
 *
 * <p>Each test is one cell of the truth table in the RFC §2.2 / §2.3.
 */
class ProviderRequirementsTest {

    @Test
    @DisplayName("OpenAI cloud: needs api key, no base url, no hint")
    void openaiCloud() {
        ProviderRequirements.Required r = ProviderRequirements.of(cloud("openai", true));
        assertTrue(r.needsApiKey());
        assertFalse(r.needsBaseUrl());
        assertNull(r.hintKey());
    }

    @Test
    @DisplayName("Kimi cloud: same shape as OpenAI")
    void kimiCloud() {
        ProviderRequirements.Required r = ProviderRequirements.of(cloud("kimi", true));
        assertTrue(r.needsApiKey());
        assertFalse(r.needsBaseUrl());
    }

    @Test
    @DisplayName("DeepSeek cloud: same shape")
    void deepseekCloud() {
        ProviderRequirements.Required r = ProviderRequirements.of(cloud("deepseek", true));
        assertTrue(r.needsApiKey());
        assertFalse(r.needsBaseUrl());
    }

    @Test
    @DisplayName("llama.cpp local: no api key, needs base url, llamacpp hint")
    void llamacppLocal() {
        ProviderRequirements.Required r = ProviderRequirements.of(local("llamacpp"));
        assertFalse(r.needsApiKey());
        assertTrue(r.needsBaseUrl());
        assertEquals("provider.hint.llamacppBaseUrlExample", r.hintKey());
        assertEquals("http://127.0.0.1:8080/v1", r.hintArgs().get("example"));
    }

    @Test
    @DisplayName("Ollama local: ollama-specific hint")
    void ollamaLocal() {
        ProviderRequirements.Required r = ProviderRequirements.of(local("ollama"));
        assertFalse(r.needsApiKey());
        assertTrue(r.needsBaseUrl());
        assertEquals("provider.hint.ollamaBaseUrlExample", r.hintKey());
        assertEquals("http://127.0.0.1:11434", r.hintArgs().get("example"));
    }

    @Test
    @DisplayName("LM Studio local: lmstudio-specific hint, also matches lm-studio / lm_studio")
    void lmstudioLocal() {
        ProviderRequirements.Required r = ProviderRequirements.of(local("lmstudio"));
        assertEquals("provider.hint.lmstudioBaseUrlExample", r.hintKey());
        assertEquals("provider.hint.lmstudioBaseUrlExample",
                ProviderRequirements.of(local("lm-studio")).hintKey());
        assertEquals("provider.hint.lmstudioBaseUrlExample",
                ProviderRequirements.of(local("lm_studio")).hintKey());
    }

    @Test
    @DisplayName("vLLM local: vllm-specific hint")
    void vllmLocal() {
        ProviderRequirements.Required r = ProviderRequirements.of(local("vllm"));
        assertEquals("provider.hint.vllmBaseUrlExample", r.hintKey());
        assertEquals("http://127.0.0.1:8000/v1", r.hintArgs().get("example"));
    }

    @Test
    @DisplayName("Custom OpenAI-compat needing API key: needs both, generic hint")
    void customOpenAiCompatNeedingKey() {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId("my-llm-server");
        p.setIsCustom(true);
        p.setIsLocal(false);
        p.setRequireApiKey(true);

        ProviderRequirements.Required r = ProviderRequirements.of(p);
        assertTrue(r.needsApiKey());
        assertTrue(r.needsBaseUrl());
        assertEquals("provider.hint.openaiCompatBaseUrlExample", r.hintKey());
    }

    @Test
    @DisplayName("Custom OpenAI-compat without API key: only base url + generic hint")
    void customOpenAiCompatNoKey() {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId("my-llm-server");
        p.setIsCustom(true);
        p.setRequireApiKey(false);

        ProviderRequirements.Required r = ProviderRequirements.of(p);
        assertFalse(r.needsApiKey());
        assertTrue(r.needsBaseUrl());
        assertEquals("provider.hint.openaiCompatBaseUrlExample", r.hintKey());
    }

    @Test
    @DisplayName("OAuth provider: no api key, no base url, no hint")
    void oauthProvider() {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId("anthropic-claude-code");
        p.setAuthType("oauth");
        p.setRequireApiKey(true);   // ignored under oauth
        p.setIsLocal(true);          // ignored under oauth

        ProviderRequirements.Required r = ProviderRequirements.of(p);
        assertFalse(r.needsApiKey());
        assertFalse(r.needsBaseUrl());
        assertNull(r.hintKey());
    }

    @Test
    @DisplayName("Generic OAuth (non-Claude-Code): same shape")
    void genericOauth() {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId("some-oauth-provider");
        p.setAuthType("oauth");

        ProviderRequirements.Required r = ProviderRequirements.of(p);
        assertFalse(r.needsApiKey());
        assertFalse(r.needsBaseUrl());
    }

    @Test
    @DisplayName("Null provider: safe defaults")
    void nullProvider() {
        ProviderRequirements.Required r = ProviderRequirements.of(null);
        assertFalse(r.needsApiKey());
        assertFalse(r.needsBaseUrl());
        assertNull(r.hintKey());
        assertNotNull(r.hintArgs());
    }

    @Test
    @DisplayName("isCustom=true with empty providerId: still needs base url, generic hint")
    void customEmptyProviderId() {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setIsCustom(true);
        p.setRequireApiKey(false);

        ProviderRequirements.Required r = ProviderRequirements.of(p);
        assertTrue(r.needsBaseUrl());
        assertEquals("provider.hint.openaiCompatBaseUrlExample", r.hintKey());
    }

    // ===== helpers =====

    private static ModelProviderEntity cloud(String id, boolean requireApiKey) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setIsLocal(false);
        p.setIsCustom(false);
        p.setRequireApiKey(requireApiKey);
        return p;
    }

    private static ModelProviderEntity local(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setIsLocal(true);
        p.setIsCustom(false);
        p.setRequireApiKey(false);
        return p;
    }
}
