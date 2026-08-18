package vip.mate.channel.web;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Generates positive event ids from a wall-clock floor and atomic sequence. */
final class SseEventIdGenerator {

    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final int COUNTER_BITS = 10;
    private static final long IDS_PER_MILLISECOND = 1L << COUNTER_BITS;
    private static final long MAX_EPOCH_MILLIS = MAX_SAFE_INTEGER / IDS_PER_MILLISECOND;

    private final LongSupplier clock;
    private final AtomicLong lastId;

    SseEventIdGenerator(LongSupplier clock) {
        this.clock = clock;
        this.lastId = new AtomicLong(epochFloor(clock.getAsLong()) - 1);
    }

    long nextId() {
        long floor = epochFloor(clock.getAsLong());
        for (;;) {
            long current = lastId.get();
            if (current >= MAX_SAFE_INTEGER) {
                throw new IllegalStateException("SSE event id space exhausted");
            }
            long next = Math.max(current + 1, floor);
            if (lastId.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private long epochFloor(long epochMillis) {
        if (epochMillis <= 0 || epochMillis > MAX_EPOCH_MILLIS) {
            throw new IllegalStateException("clock is outside the SSE event id range");
        }
        return epochMillis * IDS_PER_MILLISECOND;
    }
}
