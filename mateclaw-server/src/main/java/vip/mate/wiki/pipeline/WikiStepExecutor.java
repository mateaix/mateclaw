package vip.mate.wiki.pipeline;

/**
 * Executes one pipeline step of a given kind. Implementations register their
 * {@link #type()} (e.g. {@code llm}, {@code skill}); the pipeline service
 * dispatches each step to the matching executor.
 *
 * <p>Python execution is intentionally not provided here — it requires a real
 * OS sandbox and a separate security review, and is out of the MVP.
 *
 * @author MateClaw Team
 */
public interface WikiStepExecutor {

    /** The executor kind this handles, matched against a step's {@code executor}. */
    String type();

    /**
     * Run the step and return its textual output.
     *
     * @throws Exception on failure — the pipeline records the step as failed
     */
    String execute(WikiStepContext context) throws Exception;
}
