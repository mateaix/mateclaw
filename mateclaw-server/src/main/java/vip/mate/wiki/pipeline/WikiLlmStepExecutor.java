package vip.mate.wiki.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import vip.mate.wiki.job.WikiModelRoutingService;

import java.util.List;

/**
 * Pipeline step executor that calls a chat model. The step config supplies a
 * {@code prompt} (system instruction) and an optional {@code model_id}; the
 * previous step's output is passed as the user message so steps compose. The
 * model is resolved through the existing wiki model routing.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class WikiLlmStepExecutor implements WikiStepExecutor {

    private final WikiModelRoutingService modelRoutingService;

    public WikiLlmStepExecutor(WikiModelRoutingService modelRoutingService) {
        this.modelRoutingService = modelRoutingService;
    }

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public String execute(WikiStepContext context) {
        String prompt = stringConfig(context, "prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("llm step '" + context.stepId() + "' has no prompt");
        }
        Long modelId = longConfig(context, "model_id");
        ChatModel chatModel = modelRoutingService.buildChatModel(modelId);
        if (chatModel == null) {
            throw new IllegalStateException("No chat model available for pipeline step " + context.stepId());
        }
        String userContent = context.previousOutput() == null || context.previousOutput().isBlank()
                ? "(no prior output)" : context.previousOutput();
        ChatResponse resp = chatModel.call(new Prompt(List.of(
                new SystemMessage(prompt), new UserMessage(userContent))));
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null
                || resp.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("Chat model returned no text for step " + context.stepId());
        }
        return resp.getResult().getOutput().getText();
    }

    private String stringConfig(WikiStepContext context, String key) {
        Object v = context.stepConfig() == null ? null : context.stepConfig().get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Long longConfig(WikiStepContext context, String key) {
        Object v = context.stepConfig() == null ? null : context.stepConfig().get(key);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
