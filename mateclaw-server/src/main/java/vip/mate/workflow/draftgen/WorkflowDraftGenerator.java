package vip.mate.workflow.draftgen;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.repository.ChannelMapper;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.workflow.compiler.PublishContext;
import vip.mate.workflow.compiler.WorkflowAclPort;
import vip.mate.workflow.compiler.WorkflowCompiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Natural-language → workflow draft generator.
 *
 * <p>Composes a system prompt + workspace-scoped context (available
 * digital employees + channels) + the user description, dispatches to
 * the workspace's default chat model, parses the JSON response, and
 * runs {@link WorkflowCompiler} against it without persisting. The
 * compile pass is "preview-only" — auto-publish is explicitly
 * forbidden in the system prompt and we don't insert any rows here.
 *
 * <p>The generator is also the shared core called by the
 * {@code workflow_draft_generate} agent tool, so a chat user can ask
 * an agent "把每周一汇总销售这件事做成 workflow" and the agent gets back
 * the same draft shape.
 *
 * <p>Failures are surfaced rather than swallowed: if the model returns
 * non-JSON or the JSON doesn't carry a {@code steps} array, the
 * generator throws so the controller / tool returns a clear error
 * instead of a silently-broken draft.
 */
@Slf4j
@Service
public class WorkflowDraftGenerator {

    /** System prompt — the contract the LLM must honor. Embedded as a
     *  text block so the file is the canonical version (no resource
     *  loading, no separate prompt-management infra in v0). */
    static final String SYSTEM_PROMPT = """
            你是 MateClaw 的工作流草稿生成器。你的任务是把用户用自然语言描述的业务流程，转换成 MateClaw RFC-29 v0 workflow JSON 草稿。

            你只输出 JSON，不输出 Markdown，不输出解释，不输出代码块。

            # 输出形态

            必须输出一个 JSON object，结构如下：

            {
              "schemaVersion": "1.0",
              "name": "...",
              "description": "...",
              "metadata": {
                "generatedFrom": "natural_language",
                "confidence": 0.0,
                "warnings": [],
                "missingFields": []
              },
              "triggerDrafts": [],
              "steps": []
            }

            # v0 支持的 7 种 mode

            sequential — 一个员工执行；必须 agentId/agentName + promptTemplate。outputContentType 只能 text 或 json。
            fan_out — 至少 2 个连续 fan_out，后接 collect；每个分支必须 agentId/agentName + promptTemplate。
            collect — 不带 agentId、agentName、promptTemplate；只能跟在 fan_out group 后。
            conditional — mode.expression 必填，使用 Pebble 子集语法。
              · 比较：== != < <= > >=
              · 逻辑：必须使用单词 and / or / not，禁止 && / || / !
              · 示例（单条件）：{{ outputs.x.approved == true }}
              · 示例（多条件）：{{ outputs.finance.flag == true or outputs.ops.flag == true or outputs.customer.flag == true }}
              · 示例（取反）：{{ not outputs.x.skip }}
              agentId/agentName + promptTemplate 必填。
            await_approval — approvalKind + approverChannels[] + approvalMessage 必填；可选 timeoutSecs；不要 agentId / agentName / promptTemplate。
            dispatch_channel — channels[] + targets{} + content 必填；不要 agentId / agentName / promptTemplate。
            write_memory — employeeId + file + mergeStrategy(append/replace_section/upsert_kv/overwrite) + content 必填；不要 agentId / agentName / promptTemplate。

            # 不支持

            不要生成 loop / invoke_skill / subflow。不要生成 agent_lifecycle / content_match 触发器。
            遇到循环、重复直到成功、调用技能、复杂嵌套，用最接近的线性步骤，并在 metadata.warnings 写明需人工确认。

            # 触发器（triggerDrafts）

            只允许 patternType: cron / channel_message / workflow_completion / webhook。
            triggerDrafts 默认 enabled=false，绝不自动启用。

            # 命名

            workflow.name 用英文 kebab-case (daily-sales-summary)。
            step.name 与 outputVar 一律用英文 snake_case (collect_sales_data / sales_summary)。它们会作为标识符进入 Pebble 表达式——连字符会被解析成减号，导致工作流运行期崩溃。绝不能在 step.name 或 outputVar 里用连字符。
            description 用用户母语。

            # 跨步引用（promptTemplate / dispatch content / write_memory content / expression 里引用上游产出）

            引用上游步骤的产出，只能写 {{ outputs.<outputVar> }} —— <outputVar> 必须是某个更早步骤声明的 outputVar 字段，绝对不是 step.name。
            outputContentType 为 json 时可取子字段：{{ outputs.<outputVar>.<field> }}；为 text 时直接 {{ outputs.<outputVar> }}，不带子字段。
            正例：上游 step 的 outputVar 是 news_data → 下游写 {{ outputs.news_data }}。
            反例：{{ outputs.search-competitor-news.news_data }} —— 既用了 step.name 又带了连字符，必然运行失败。

            # 占位字段

            找不到匹配的真实 ID/渠道/员工时使用占位：
            - agentName: "TODO_*_AGENT"
            - employeeId: "TODO_EMPLOYEE_ID"
            - channels[*]: "TODO_SELECT_CHANNEL"
            - targets["TODO_SELECT_CHANNEL"]: "TODO_TARGET_ID"
            - sourceWorkflowId: "TODO_WORKFLOW_ID"
            每个 TODO 都要在 metadata.missingFields 中解释。
            绝不能编造不存在的 agentId / channelType / 群 ID。

            # 默认值

            approvalKind: manager / finance / manual / legal / oncall 之一。
            approverChannels: 默认 ["web"]，除非用户明确说企业 IM 渠道。
            mergeStrategy: 默认 "append"。
            schemaVersion: 始终 "1.0"。

            # 质量

            只使用 v0 字段；无注释；无 trailing comma；无 Markdown；不自动启用 trigger；不自动发布。
            """;

    private final ProviderChatModelFactory chatModelFactory;
    private final ModelConfigService modelConfigService;
    private final RetryTemplate retryTemplate;
    private final AgentMapper agentMapper;
    private final ChannelMapper channelMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowCompiler compiler;
    private final WorkflowAclPort aclPort;
    private final WorkflowDraftTemplateLibrary templateLibrary;

    public WorkflowDraftGenerator(ProviderChatModelFactory chatModelFactory,
                                  ModelConfigService modelConfigService,
                                  RetryTemplate retryTemplate,
                                  AgentMapper agentMapper,
                                  ChannelMapper channelMapper,
                                  ObjectMapper objectMapper,
                                  WorkflowCompiler compiler,
                                  WorkflowAclPort aclPort,
                                  WorkflowDraftTemplateLibrary templateLibrary) {
        this.chatModelFactory = chatModelFactory;
        this.modelConfigService = modelConfigService;
        this.retryTemplate = retryTemplate;
        this.agentMapper = agentMapper;
        this.channelMapper = channelMapper;
        this.objectMapper = objectMapper;
        this.compiler = compiler;
        this.aclPort = aclPort;
        this.templateLibrary = templateLibrary;
    }

    public GeneratedWorkflowDraft generate(String description, long workspaceId) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be empty");
        }

        // --- 1. workspace context ---------------------------------------
        String contextPrompt = buildContextPrompt(workspaceId);

        // --- 2. resolve runtime model ----------------------------------
        ModelConfigEntity model = modelConfigService.getDefaultModel();
        if (model == null) {
            throw new IllegalStateException(
                    "No default chat model configured; cannot generate workflow draft");
        }
        ChatModel chatModel = chatModelFactory.buildFor(model, retryTemplate);
        ChatClient client = ChatClient.create(chatModel);

        // --- 3. call the model -----------------------------------------
        String raw;
        try {
            raw = client.prompt()
                    .system(SYSTEM_PROMPT + "\n\n" + contextPrompt)
                    .user(description)
                    .call()
                    .content();
        } catch (Exception e) {
            // Log the full cause chain — the controller / agent tool only
            // surface getMessage(), so without this a provider-side failure
            // (auth, NPE in the chat client, ...) leaves no stack trace.
            log.error("Workflow draft generator chat call failed", e);
            throw new IllegalStateException(
                    "Workflow draft generator chat call failed: " + e.getMessage(), e);
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Workflow draft generator returned empty content");
        }

        // --- 4. parse + validate shape ---------------------------------
        JsonNode root = parseStrict(raw);
        if (!root.has("steps") || !root.get("steps").isArray()) {
            throw new IllegalStateException(
                    "Generated draft has no steps[] array; raw output: " + truncate(raw));
        }

        // --- 5. extract fields -----------------------------------------
        String name = root.path("name").asText("");
        String userDescription = root.path("description").asText("");
        Double confidence = root.path("metadata").path("confidence").isNumber()
                ? root.path("metadata").path("confidence").asDouble() : null;

        List<String> warnings = readStringArray(root, "metadata", "warnings");
        List<String> missingFields = readStringArray(root, "metadata", "missingFields");

        // The runtime only consumes the steps part of the draft — strip
        // everything else into a clean {steps:[...]} shape.
        Map<String, Object> draftRoot = new LinkedHashMap<>();
        draftRoot.put("steps", objectMapper.convertValue(root.get("steps"),
                new TypeReference<List<Map<String, Object>>>() {}));
        String draftJson;
        try {
            draftJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(draftRoot);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to re-serialize generated steps: " + e.getMessage(), e);
        }

        // --- 6. trigger drafts -----------------------------------------
        // patternType allowlist mirrors what TriggerService accepts at
        // create time. The generator prompt forbids agent_lifecycle and
        // content_match; we filter defensively here too because models
        // occasionally hallucinate trigger types under low confidence,
        // and we don't want a future UI / tool that calls /draft/generate
        // and trusts the response to silently re-introduce dropped types.
        java.util.Set<String> allowedPatternTypes = java.util.Set.of(
                "cron", "channel_message", "workflow_completion", "webhook");
        List<Map<String, Object>> triggerDrafts = new ArrayList<>();
        if (root.has("triggerDrafts") && root.get("triggerDrafts").isArray()) {
            List<Map<String, Object>> candidates = objectMapper.convertValue(root.get("triggerDrafts"),
                    new TypeReference<List<Map<String, Object>>>() {});
            int dropped = 0;
            for (Map<String, Object> td : candidates) {
                String pt = td.get("patternType") instanceof String s ? s : null;
                if (pt == null || !allowedPatternTypes.contains(pt)) {
                    dropped++;
                    continue;
                }
                // Belt-and-suspenders: never trust the LLM to honor enabled=false.
                td.put("enabled", false);
                triggerDrafts.add(td);
            }
            if (dropped > 0) {
                warnings = appendWarning(warnings,
                        "dropped " + dropped + " unsupported triggerDraft entr" + (dropped == 1 ? "y" : "ies")
                                + " (allowed: " + String.join(", ", allowedPatternTypes) + ")");
            }
        }

        // --- 7. compile preview ---------------------------------------
        boolean compileOk;
        List<vip.mate.workflow.compiler.CompileError> compileErrors;
        try {
            // PublishContext is (workspaceId, publisherId).
            WorkflowCompiler.Result result = compiler.compile(draftJson,
                    new PublishContext(workspaceId, 0L), aclPort);
            compileOk = result.ok();
            compileErrors = compileOk ? List.of() : result.errors();
        } catch (Exception e) {
            // Compile preview failures are not fatal — the operator can
            // still edit the draft. We surface them as warnings.
            log.warn("[WorkflowDraftGenerator] preview compile failed: {}", e.getMessage());
            compileOk = false;
            compileErrors = List.of();
            warnings = appendWarning(warnings, "preview compile threw: " + e.getMessage());
        }

        return new GeneratedWorkflowDraft(
                name == null || name.isBlank() ? "untitled-workflow" : name,
                userDescription,
                draftJson,
                triggerDrafts,
                warnings,
                missingFields,
                confidence,
                compileOk,
                compileErrors);
    }

    /** Compose the workspace-scoped context prompt: agent + channel
     *  inventory the model can pick from. Agents are filtered to enabled
     *  rows; channels likewise. The model is told to prefer real ids
     *  over TODOs but never to fabricate. */
    private String buildContextPrompt(long workspaceId) {
        List<AgentEntity> agents = agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .eq(AgentEntity::getEnabled, true));
        List<ChannelEntity> channels = channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getWorkspaceId, workspaceId)
                .eq(ChannelEntity::getEnabled, true));

        StringBuilder sb = new StringBuilder();
        sb.append("# 当前 workspace 可用数字员工\n[");
        boolean first = true;
        for (AgentEntity a : agents) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"agentId\":").append(a.getId())
              .append(",\"name\":\"").append(escape(a.getName()))
              .append("\",\"description\":\"")
              .append(escape(a.getDescription() == null ? "" : a.getDescription()))
              .append("\"}");
        }
        sb.append("]\n\n# 当前 workspace 可用渠道\n[");
        first = true;
        for (ChannelEntity c : channels) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"channelType\":\"").append(escape(c.getChannelType()))
              .append("\",\"name\":\"").append(escape(c.getName()))
              .append("\"}");
        }
        sb.append("]\n\n优先使用这些真实 agentId 和 channelType。不存在的 ID 必须用 TODO_* 占位，不要编造。\n");

        // Few-shot exemplars from the template library — the LLM stays
        // closer to canonical shapes when it has 2-3 concrete examples
        // in the system prompt.
        sb.append("\n# 模板示例（参考，不必照抄）\n");
        for (WorkflowDraftTemplate t : templateLibrary.all()) {
            sb.append("## ").append(t.id()).append(" — ").append(t.label()).append("\n");
            sb.append(t.description()).append("\n");
            sb.append("draft: ").append(t.draftJson()).append("\n");
            if (t.triggerDraftsJson() != null && !"[]".equals(t.triggerDraftsJson())) {
                sb.append("triggerDrafts: ").append(t.triggerDraftsJson()).append("\n");
            }
        }
        return sb.toString();
    }

    private JsonNode parseStrict(String raw) {
        // Some models still wrap the JSON in a ```json fence even when
        // the prompt says "no Markdown". Strip the fences before parsing
        // so we don't reject otherwise-valid output.
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNl = cleaned.indexOf('\n');
            if (firstNl > 0) cleaned = cleaned.substring(firstNl + 1);
            int closeFence = cleaned.lastIndexOf("```");
            if (closeFence > 0) cleaned = cleaned.substring(0, closeFence);
            cleaned = cleaned.trim();
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Workflow draft generator returned non-JSON: " + e.getMessage()
                            + " — raw: " + truncate(raw), e);
        }
    }

    private List<String> readStringArray(JsonNode root, String... path) {
        JsonNode node = root;
        for (String p : path) node = node.path(p);
        if (!node.isArray()) return List.of();
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item.isTextual()) out.add(item.asText());
        }
        return out;
    }

    private static List<String> appendWarning(List<String> existing, String msg) {
        List<String> next = new ArrayList<>(existing == null ? List.of() : existing);
        next.add(msg);
        return next;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
