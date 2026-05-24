package vip.mate.agent.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single step inside a conversation's {@link ProgressLedger}.
 *
 * <p>{@code key} is the stable identifier the agent picks (e.g. {@code
 * "model_gpt55"} for "research GPT-5.5" or {@code "step_pptx"} for "generate
 * the slide deck"). The same key on subsequent updates overwrites the entry
 * in place so the model can advance one step from {@code PENDING} →
 * {@code IN_PROGRESS} → {@code DONE} without producing duplicates.
 *
 * <p>{@code note} is optional and capped at a few hundred characters when
 * rendered into the snapshot; the field itself isn't length-limited because
 * the underlying column is LONGTEXT and a model that wants to dump rich
 * context shouldn't be silently truncated at the schema layer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProgressEntry {

    private String key;
    private String label;
    private ProgressStatus status;
    private String note;
    private Instant updatedAt;
}
