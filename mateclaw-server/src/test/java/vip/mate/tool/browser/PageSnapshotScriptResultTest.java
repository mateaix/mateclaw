package vip.mate.tool.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageSnapshotScriptResultTest {

    @Test
    @DisplayName("parses ref metadata and keeps refs backward-compatible")
    void parsesRefMetadata() {
        PageSnapshotScript.Result result = PageSnapshotScript.Result.fromJson("""
                {
                  "tree":"- button \\"Save\\" @e1",
                  "truncated":false,
                  "refs":["e1"],
                  "refInfos":[
                    {"ref":"e1","role":"button","name":"Save","tag":"button","type":"","href":"","value":"","checked":false,"selected":false,"disabled":false,"expanded":true}
                  ]
                }
                """);

        assertEquals("e1", result.refs().getFirst());
        assertEquals(1, result.refInfos().size());
        PageSnapshotScript.RefFingerprint ref = result.refInfos().get("e1");
        assertEquals("button", ref.role());
        assertEquals("Save", ref.name());
        assertEquals("button", ref.tag());
        assertTrue(ref.expanded());
    }

    @Test
    @DisplayName("detects core ref identity changes")
    void detectsCoreIdentityChanges() {
        PageSnapshotScript.RefFingerprint before = new PageSnapshotScript.RefFingerprint(
                "e1", "button", "Save", "button", "", "", "", false, false, false, null);
        PageSnapshotScript.RefFingerprint same = new PageSnapshotScript.RefFingerprint(
                "e1", "button", "Save", "button", "", "", "new value", false, false, false, null);
        PageSnapshotScript.RefFingerprint changed = new PageSnapshotScript.RefFingerprint(
                "e1", "link", "Save", "a", "", "/save", "", false, false, false, null);

        assertTrue(before.sameCoreIdentity(same));
        assertFalse(before.sameCoreIdentity(changed));
    }

    @Test
    @DisplayName("snapshot and live fingerprint scripts normalize long names identically")
    void fingerprintScriptsShareNameNormalization() {
        assertTrue(PageSnapshotScript.SNAPSHOT_JS.contains("normalizeName(nameOf(el))"));
        assertTrue(PageSnapshotScript.REF_FINGERPRINT_JS.contains("normalizeName(nameOf(el))"));
    }
}
