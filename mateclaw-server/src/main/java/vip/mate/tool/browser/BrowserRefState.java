package vip.mate.tool.browser;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thread-safe lifecycle state for snapshot references in one browser session.
 */
public final class BrowserRefState {

    public enum Status {
        NONE,
        VALID,
        INVALIDATED
    }

    private int snapshotGeneration;
    private long navigationEpoch;
    private long snapshotNavigationEpoch = -1;
    private String currentUrl = "";
    private String snapshotUrl = "";
    private Status status = Status.NONE;
    private Set<String> refs = Set.of();
    private Map<String, PageSnapshotScript.RefFingerprint> refInfos = Map.of();

    public synchronized int recordSnapshot(
            String url,
            List<String> newRefs,
            Map<String, PageSnapshotScript.RefFingerprint> newRefInfos) {
        currentUrl = normalizeUrl(url);
        snapshotUrl = currentUrl;
        snapshotNavigationEpoch = navigationEpoch;
        refs = Set.copyOf(newRefs);
        refInfos = Map.copyOf(newRefInfos);
        status = Status.VALID;
        return ++snapshotGeneration;
    }

    public synchronized void onMainFrameNavigated(String url) {
        currentUrl = normalizeUrl(url);
        navigationEpoch++;
        invalidateSnapshot();
    }

    public synchronized void reconcileUrl(String url) {
        String normalized = normalizeUrl(url);
        if (!currentUrl.isEmpty() && !currentUrl.equals(normalized)) {
            onMainFrameNavigated(normalized);
            return;
        }
        currentUrl = normalized;
    }

    public synchronized void invalidate() {
        invalidateSnapshot();
    }

    private void invalidateSnapshot() {
        refs = Set.of();
        refInfos = Map.of();
        status = snapshotGeneration == 0 ? Status.NONE : Status.INVALIDATED;
    }

    private static String normalizeUrl(String url) {
        return url == null ? "" : url;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized boolean refsValid() {
        return status == Status.VALID && snapshotNavigationEpoch == navigationEpoch;
    }

    public synchronized boolean contains(String ref) {
        return refs.contains(ref);
    }

    public synchronized PageSnapshotScript.RefFingerprint fingerprint(String ref) {
        return refInfos.get(ref);
    }

    public synchronized int refCount() {
        return refs.size();
    }

    public synchronized int snapshotGeneration() {
        return snapshotGeneration;
    }

    public synchronized long navigationEpoch() {
        return navigationEpoch;
    }

    public synchronized long snapshotNavigationEpoch() {
        return snapshotNavigationEpoch;
    }

    public synchronized String currentUrl() {
        return currentUrl;
    }

    public synchronized String snapshotUrl() {
        return snapshotUrl;
    }
}
