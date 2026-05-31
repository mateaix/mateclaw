package vip.mate.wiki.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Schema for one pageType metadata field within a {@link WikiPageTypeDef}.
 *
 * @author MateClaw Team
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WikiFieldSchema {

    /** Field type: string / number / boolean / date / enum / string_array. */
    private String type;

    /** Whether the field must be present and non-empty. */
    private boolean required;

    /** Allowed values when {@link #type} is {@code enum}. */
    private List<String> values;
}
