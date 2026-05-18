package vip.mate.llm.chatmodel;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays per-request assistant {@code reasoning_content} from the producer
 * ({@code NodeStreamingChatHelper}, which sees {@code AssistantMessage.metadata})
 * to the consumer ({@link OpenAiRequestRewriter#patchReasoningContent}, which
 * rebuilds the outbound {@code ChatCompletionRequest}).
 *
 * <p>Why not {@link ThreadLocal}: {@code OpenAiChatModel.stream()} hops to
 * {@code boundedElastic} via {@code subscribeOn}, so a {@code ThreadLocal} on the
 * caller does not propagate across the producer/consumer boundary. The relay
 * token travels inside the request object itself
 * ({@code OpenAiApi.ChatCompletionRequest.user}), which survives scheduler hops
 * without needing Reactor context propagation config.
 *
 * <p>The {@link RelayEntry} carries both the per-assistant thinking list and the
 * caller's <em>original</em> {@code user} field — the producer overwrites
 * {@code OpenAiChatOptions.user} with the relay token before handing the
 * {@code Prompt} to Spring AI, so by the time the consumer runs,
 * {@code request.user()} only contains the token. The consumer restores the
 * caller's original value from the entry when rebuilding the outbound request.
 * The internal token is never sent to the provider.
 *
 * <p>Ownership: the producer is responsible for calling {@link #discard(String)}
 * in a {@code finally} block as a belt-and-suspenders cleanup. The consumer's
 * {@link #take(String)} already removes the entry on the happy path, so
 * {@code discard} is a no-op in that case; it becomes the only cleanup when the
 * consumer never runs (e.g., a Reactor error before the request is dispatched).
 *
 * @author MateClaw Team
 */
public final class AssistantThinkingRelay {

    /**
     * Per-request relay payload.
     *
     * @param thinkings    per-assistant {@code reasoning_content} in message order;
     *                     empty string means "this assistant had no thinking"
     * @param originalUser the caller's original {@code OpenAiChatOptions.user} value
     *                     before the producer overwrote it with the relay token;
     *                     may be {@code null}
     */
    public record RelayEntry(List<String> thinkings, String originalUser) {
        public RelayEntry {
            thinkings = List.copyOf(thinkings);
        }
    }

    private static final ConcurrentHashMap<String, RelayEntry> MAP = new ConcurrentHashMap<>();

    /** Prefix must be distinctive enough that a caller-provided {@code user} value
     *  can never collide with a relay token. */
    public static final String TOKEN_PREFIX = "__mc_thinking_";

    private AssistantThinkingRelay() {}

    /**
     * Stash per-assistant thinking (in message order) plus the caller's original
     * {@code user} field. Returns the token to embed in
     * {@code OpenAiChatOptions.user}.
     */
    public static String stash(List<String> thinkingsInOrder, String originalUser) {
        String token = TOKEN_PREFIX + UUID.randomUUID();
        MAP.put(token, new RelayEntry(thinkingsInOrder, originalUser));
        return token;
    }

    /** Consume and remove entry. Returns {@code null} if {@code user} is not a
     *  relay token or the entry was already taken. */
    public static RelayEntry take(String user) {
        if (!isToken(user)) return null;
        return MAP.remove(user);
    }

    /** Whether the given {@code user} field value is a relay token produced by
     *  {@link #stash(List, String)}. */
    public static boolean isToken(String user) {
        return user != null && user.startsWith(TOKEN_PREFIX);
    }

    /** Defensive cleanup; idempotent — safe to call even after {@link #take}. */
    public static void discard(String token) {
        if (token != null) MAP.remove(token);
    }

    // ---------- test hooks ----------

    /** Visible for tests: current map size. Production code must not use. */
    static int size() {
        return MAP.size();
    }

    /** Visible for tests: clear all entries. Production code must not use. */
    static void clearAll() {
        MAP.clear();
    }
}
