package vip.mate.wiki.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A parsed KB pageType profile: the page types a KB recognises plus
 * profile-wide options. Deserialized from a profile row's {@code config_json}
 * or supplied as the built-in default by
 * {@link WikiPageTypeProfileService}.
 *
 * @author MateClaw Team
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WikiPageTypeProfile {

    /** Config schema version. */
    private int version = 1;

    /** pageType name (lowercase) → definition. Insertion order preserved. */
    private Map<String, WikiPageTypeDef> pageTypes = new LinkedHashMap<>();

    /**
     * When a routed/created page declares a type absent from {@link #pageTypes},
     * it is downgraded to this type. Defaults to {@code concept}.
     */
    private String fallbackType = "concept";

    /**
     * When {@code true}, metadata fields not declared in a type's schema are
     * kept; otherwise they are dropped (with a validation warning).
     */
    private boolean allowAdditionalFields = false;

    /**
     * Normalise keys to lowercase on set so a user-authored profile with an
     * uppercase pageType key (e.g. {@code "Concept"}) still matches the
     * case-insensitive {@link #hasPageType}/{@link #get} lookups. Replaces the
     * Lombok-generated setter (so Jackson deserialization goes through here too).
     */
    public void setPageTypes(Map<String, WikiPageTypeDef> pageTypes) {
        Map<String, WikiPageTypeDef> normalized = new LinkedHashMap<>();
        if (pageTypes != null) {
            for (Map.Entry<String, WikiPageTypeDef> e : pageTypes.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                String key = e.getKey().trim().toLowerCase();
                if (!key.isEmpty()) {
                    normalized.put(key, e.getValue());
                }
            }
        }
        this.pageTypes = normalized;
    }

    /** Whether this profile declares the given pageType (case-insensitive). */
    public boolean hasPageType(String pageType) {
        if (pageType == null) {
            return false;
        }
        return pageTypes.containsKey(pageType.trim().toLowerCase());
    }

    /** Lookup a definition by name (case-insensitive), or {@code null}. */
    public WikiPageTypeDef get(String pageType) {
        if (pageType == null) {
            return null;
        }
        return pageTypes.get(pageType.trim().toLowerCase());
    }
}
