package vip.mate.llm.probe;

import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.Optional;

/**
 * SPI for probing the real context-window size of a locally hosted model
 * (Ollama, vLLM, LM Studio, MLX and other self-hosted OpenAI-compatible
 * servers).
 *
 * <p>Motivation: {@code ModelConfigEntity.maxInputTokens} is optional and
 * rarely filled in for local deployments, so the conversation window budget
 * silently falls back to the global default (128k). A local 8k/16k model then
 * never triggers any trimming and the first oversized request fails. Probing
 * the serving endpoint recovers the true window without user configuration.
 *
 * <p>Contract: implementations must be cheap to call (single short HTTP
 * request), must never throw for routine failures (return
 * {@link Optional#empty()} instead), and must not be invoked for cloud
 * providers — {@link #supports} gates that.
 */
public interface LocalContextProbe {

    /**
     * @return true when this probe knows how to query the given provider.
     *         Implementations must return false for cloud providers so no
     *         probe traffic ever leaves the local network.
     */
    boolean supports(ModelProviderEntity provider, ModelConfigEntity model);

    /**
     * Query the serving endpoint for the model's maximum context length.
     *
     * @return the context window in tokens, or empty when the endpoint is
     *         unreachable, the model is unknown, or the response carries no
     *         usable length field.
     */
    Optional<Integer> probeContextLength(ModelProviderEntity provider, ModelConfigEntity model);
}
