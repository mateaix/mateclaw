package vip.mate.channel;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import vip.mate.agent.ContentKind;

import java.util.List;
import java.util.Map;

/**
 * Single authority for the "provisional narration" lifecycle shared by every
 * user-facing surface.
 *
 * <p>A {@link ContentKind#PRE_TOOL_NARRATION} span is written before any tool
 * observation of its turn, in a completion that goes on to call tools — it may
 * be process narration or a fully fabricated rehearsal of the result. The
 * policy, identical everywhere:
 *
 * <ul>
 *   <li>stage it instead of publishing (it may stay visible transiently, e.g.
 *       in a live progress bubble or a running SSE segment);</li>
 *   <li>the turn's next content span (grounded narration or final answer)
 *       supersedes it — it must not become permanent output;</li>
 *   <li>if the turn ends with no later content at all, it is committed: with
 *       no replacement it is everything the user gets.</li>
 * </ul>
 *
 * <p>Grounded narrations and final answers never stage — they publish
 * directly. Producers that predate the kind tag emit {@code null} kinds; the
 * streaming API accepts a caller-supplied structural fallback signal for that
 * case, and the segment-marking API leaves untagged timelines to the legacy
 * structural detector.
 *
 * <p>Instances are single-turn and not thread-safe — create one per stream
 * consumption, confine to the consuming thread (matches how channel adapters
 * drain a turn's {@code Flux} today).
 */
@Slf4j
public final class ProvisionalContentTracker {

    /** Marker value stored in {@code supersededReason} — same wire value the
     *  legacy structural detector writes, so the UI needs no new vocabulary. */
    public static final String REASON_PRE_TOOL_CONTENT_REPLACED =
            "pre_tool_content_replaced_by_post_tool_answer";

    private static final String METRIC_SUPERSEDED = "mateclaw.narration.superseded";

    /** Where the supersede happened — metric tag, one value per surface. */
    private final String surface;

    /** Tool observations completed so far this turn (caller-reported). */
    private int observations;
    /** Observation count at the time of the most recent staging. */
    private int lastStageMark;

    private String pendingText;
    private boolean pendingProvisional;
    /** Observation count when the pending narration was staged. */
    private int pendingMark;

    public ProvisionalContentTracker(String surface) {
        this.surface = surface;
    }

    /** Report a completed tool observation (a {@code tool_call_completed} event). */
    public void onToolObservation() {
        observations++;
    }

    /**
     * Stage a per-round narration. Returns the <em>previous</em> staged
     * narration if the new arrival makes it publishable, or {@code null} when
     * there is nothing to publish (no previous, or the previous was
     * provisional and tool observations since its staging mean this later
     * content supersedes it).
     *
     * @param kind producer-assigned kind; {@code null} for pre-tag producers,
     *             in which case a narration counts as provisional when no
     *             observation completed since the previous staging (the
     *             pre-tag online rule)
     */
    public String stageNarration(String text, ContentKind kind) {
        boolean observedSinceLast = observations > lastStageMark;
        boolean provisional = kind != null
                ? kind == ContentKind.PRE_TOOL_NARRATION
                : !observedSinceLast;
        String previous = pendingText;
        boolean previousProvisional = pendingProvisional;
        int previousMark = pendingMark;
        pendingText = text;
        pendingProvisional = provisional;
        pendingMark = observations;
        lastStageMark = observations;
        if (previous == null) {
            return null;
        }
        if (previousProvisional && observations > previousMark) {
            recordSuperseded(previous);
            return null;
        }
        return previous;
    }

    /**
     * Resolve the staged narration at turn end. Returns the text to publish,
     * or {@code null} when nothing remains (no staged narration, or it was
     * provisional, tools ran after it, and the turn produced final content
     * that replaces it).
     *
     * @param hasFinalContent whether the turn produced a final answer — with
     *                        one, a provisional narration is superseded; with
     *                        none, even a provisional narration commits (no
     *                        replacement exists)
     */
    public String settle(boolean hasFinalContent) {
        String text = pendingText;
        boolean provisional = pendingProvisional;
        int mark = pendingMark;
        pendingText = null;
        pendingProvisional = false;
        pendingMark = 0;
        if (text == null) {
            return null;
        }
        if (provisional && hasFinalContent && observations > mark) {
            recordSuperseded(text);
            return null;
        }
        return text;
    }

    private void recordSuperseded(String text) {
        log.info("[{}] provisional narration superseded by later content ({} chars dropped from permanent output)",
                surface, text.length());
        Metrics.counter(METRIC_SUPERSEDED, "surface", surface).increment();
    }

    // ==================== Persisted-timeline marking ====================

    /**
     * Whether the persisted segments timeline carries producer-assigned kind
     * tags — i.e. whether {@link #markSuperseded(List, String)} is applicable
     * or the caller should fall back to structural detection.
     */
    public static boolean hasKindTags(List<Map<String, Object>> segments) {
        if (segments == null) {
            return false;
        }
        for (Map<String, Object> seg : segments) {
            if ("content".equals(seg.get("type")) && seg.get("kind") != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kind-driven counterpart of the structural supersede scan: a
     * {@code pre_tool_narration} content segment is marked superseded by the
     * first content segment that follows it; grounded narrations and final
     * answers are never marked. Mutates segment maps in place with the same
     * three keys the structural detector writes ({@code superseded},
     * {@code supersededBySegmentId}, {@code supersededReason}).
     */
    public static void markSuperseded(List<Map<String, Object>> segments, String surface) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        String preToolWire = ContentKind.PRE_TOOL_NARRATION.wireName();
        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> seg = segments.get(i);
            if (!"content".equals(seg.get("type"))
                    || !preToolWire.equals(seg.get("kind"))
                    || Boolean.TRUE.equals(seg.get("superseded"))) {
                continue;
            }
            Map<String, Object> replacement = nextContent(segments, i + 1);
            if (replacement == null) {
                continue; // turn produced no later content — the narration stands
            }
            seg.put("superseded", true);
            seg.put("supersededBySegmentId", String.valueOf(replacement.getOrDefault("id", "")));
            seg.put("supersededReason", REASON_PRE_TOOL_CONTENT_REPLACED);
            log.info("[{}] provisional narration segment {} superseded by segment {}",
                    surface, seg.get("id"), replacement.get("id"));
            Metrics.counter(METRIC_SUPERSEDED, "surface", surface).increment();
        }
    }

    private static Map<String, Object> nextContent(List<Map<String, Object>> segments, int from) {
        for (int i = from; i < segments.size(); i++) {
            if ("content".equals(segments.get(i).get("type"))) {
                return segments.get(i);
            }
        }
        return null;
    }
}
