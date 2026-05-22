package vip.mate.goal.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Persistent goal lifecycle states.
 *
 * <p>State machine:
 * <pre>
 *   create -&gt; active
 *   active &lt;-&gt; paused
 *   active -&gt; { completed | abandoned | exhausted }   (terminal states)
 * </pre>
 *
 * <p>The persisted DB value is the lowercase string carried by
 * {@link #value} via the MyBatis Plus {@link EnumValue} annotation. This
 * is load-bearing — the V120 migration's predicate unique index (H2) and
 * generated-column unique index (MySQL) both compare {@code status =
 * 'active'} as a literal string. Writing the enum's {@link #name()} (all
 * uppercase) would silently bypass the uniqueness guarantee and let
 * concurrent creates land two ACTIVE rows on the same conversation.
 */
public enum GoalStatus {

    ACTIVE("active"),
    PAUSED("paused"),
    COMPLETED("completed"),
    ABANDONED("abandoned"),
    EXHAUSTED("exhausted");

    @EnumValue
    private final String value;

    GoalStatus(String value) {
        this.value = value;
    }

    /**
     * {@link JsonValue} pins the JSON-wire form to the same lowercase string
     * the DB stores via {@link EnumValue}. Without this, Jackson defaults to
     * {@link #name()} (uppercase) and the frontend's
     * {@code status: 'active' | 'paused' | ...} TS literal types reject
     * every payload — UI bug observed during PR4 manual QA.
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Accept both lowercase wire strings ("active") and uppercase Java
     * names ("ACTIVE") on the inbound path so client code that constructs
     * payloads either way doesn't 400.
     */
    @JsonCreator
    public static GoalStatus fromJson(String raw) {
        if (raw == null) return null;
        for (GoalStatus s : values()) {
            if (s.value.equalsIgnoreCase(raw) || s.name().equalsIgnoreCase(raw)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown GoalStatus: " + raw);
    }

    /** Terminal states do not transition; they free the conversation
     *  uniqueness slot for a fresh active goal. */
    public boolean isTerminal() {
        return this == COMPLETED || this == ABANDONED || this == EXHAUSTED;
    }
}
