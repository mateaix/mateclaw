package vip.mate.tool.browser;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-size striped lock gate for serializing operations on one browser session.
 */
public final class BrowserSessionGate {

    private final ReentrantLock[] locks;

    public BrowserSessionGate(int stripes) {
        if (stripes <= 0) {
            throw new IllegalArgumentException("stripes must be positive");
        }
        locks = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            locks[i] = new ReentrantLock(true);
        }
    }

    public Lease enter(String sessionKey) {
        int index = Math.floorMod(sessionKey.hashCode(), locks.length);
        ReentrantLock lock = locks[index];
        lock.lock();
        return lock::unlock;
    }

    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
