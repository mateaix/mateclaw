package vip.mate.skill.lifecycle;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;

/**
 * Thrown by manual-archive when the requested action would impact resources
 * the caller has not explicitly opted in to touching (a skill that is still
 * explicitly bound to one or more enabled agents).
 *
 * <p>The caller resolves the conflict by retrying with {@code force=true}.
 * {@link ResponseStatus} maps this to HTTP 409 so clients can branch on the
 * status code rather than parsing the body.
 *
 * @author MateClaw Team
 */
@Getter
@ResponseStatus(HttpStatus.CONFLICT)
public class ConfirmRequiredException extends RuntimeException {

    private final String code;
    private final List<AgentRow> boundAgents;

    public ConfirmRequiredException(String code, String message, List<AgentRow> boundAgents) {
        super(message);
        this.code = code;
        this.boundAgents = boundAgents == null ? List.of() : List.copyOf(boundAgents);
    }

    /** Minimal agent identity surfaced to the client so it can render a confirm dialog. */
    public record AgentRow(Long id, String name) {}
}
