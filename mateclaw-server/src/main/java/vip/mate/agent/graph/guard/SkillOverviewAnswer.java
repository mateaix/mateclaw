package vip.mate.agent.graph.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.graph.state.DirectToolOutput;
import java.util.*;

/** Deterministic terminal policy for narrowly recognized, read-only catalog questions. */
public final class SkillOverviewAnswer {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> QUESTIONS=Set.of("你有哪些技能","你有什么技能","有哪些技能","你会哪些技能",
            "列出可用技能","请列出可用技能","what skills do you have","list available skills");
    private SkillOverviewAnswer() {}

    public static Optional<DirectToolOutput> from(String objective,List<AssistantMessage.ToolCall> calls,
            List<ToolResponseMessage.ToolResponse> responses,ActionExecutionLedger ledger) {
        String question=TaskFinalizationCoordinator.currentObjective(objective);
        if(question==null)return Optional.empty();
        question=question.strip().toLowerCase(Locale.ROOT).replaceFirst("[?？。.!！]+$", "").strip();
        if(!QUESTIONS.contains(question) || calls.size()!=1 || responses.size()!=1)return Optional.empty();
        var call=calls.getFirst();var response=responses.getFirst();
        var receipt=ledger.receipts().get(call.id());
        if(receipt==null || receipt.status()!=ActionExecutionLedger.Status.SUCCEEDED
                || !"listAvailableSkills".equals(receipt.toolName()) || !call.id().equals(response.id()))return Optional.empty();
        if(ledger.receipts().values().stream().anyMatch(r->r.substantive() && !"listAvailableSkills".equals(r.toolName())))return Optional.empty();
        try {
            JsonNode args=JSON.readTree(call.arguments());
            String name=call.name();
            if("tool_call".equals(name)) {
                name=args.path("toolName").asText();args=args.has("arguments")?args.get("arguments"):args.path("args");
                if(args.isMissingNode() || args.isNull())args=JSON.createObjectNode();
                if(args.isTextual())args=JSON.readTree(args.asText());
            }
            if(!"listAvailableSkills".equals(name) || !args.isObject())return Optional.empty();
            if(!args.path("keyword").asText("").isBlank() || args.path("offset").asInt(0)!=0)return Optional.empty();
            for(String key:List.of("source","status"))
                if(!args.path(key).asText("").isBlank() && !"all".equalsIgnoreCase(args.path(key).asText()))return Optional.empty();
            String canonical=ledger.latestCompleteResult().get(call.id());
            if(canonical==null && receipt.resultChars()>=0 && receipt.resultChars()<=512)canonical=receipt.resultSummary();
            if(canonical==null)return Optional.empty();
            JsonNode page=JSON.readTree(canonical);
            if(page.isTextual())page=JSON.readTree(page.asText());
            if(!"skill_catalog".equals(page.path("kind").asText()) || !page.path("matchingTotal").isIntegralNumber() || !page.path("matchingTotal").canConvertToInt()
                    || page.path("matchingTotal").asInt()<0 || !page.path("names").isArray()
                    || page.path("names").size()>200 || page.path("names").size()>page.path("matchingTotal").asInt())return Optional.empty();
            int total=page.path("matchingTotal").asInt();
            StringBuilder answer=new StringBuilder("当前技能目录共 **"+total+" 项**。");
            if(total==0)return Optional.of(new DirectToolOutput(call.id(),receipt.toolName(),answer.toString(),receipt.completedAt()));
            answer.append("以下列出代表性技能及用途：\n\n| 技能 | 用途 |\n| --- | --- |\n");
            int shown=0;
            for(JsonNode item:page.path("names")) {
                if(shown==20)break;
                if(!item.isTextual())return Optional.empty();
                String skill=item.asText(), summary=page.path("summaries").path(skill).asText("");
                String line="| "+cell(skill)+" | "+cell(summary)+" |\n";
                if(answer.length()+line.length()>7500)break;
                answer.append(line);shown++;
            }
            if(shown==0)return Optional.empty();
            answer.append("\n本次展示 ").append(shown).append(" 项，共 ").append(total).append(" 项。具体使用时再按需加载对应技能。");
            return Optional.of(new DirectToolOutput(call.id(),receipt.toolName(),answer.toString(),receipt.completedAt()));
        } catch(Exception invalid) {return Optional.empty();}
    }
    private static String cell(String value) {
        return value.replace("\\","\\\\").replace("|","\\|").replace("`","\\`")
                .replace("[","\\[").replace("]","\\]").replace("<","&lt;").replace(">","&gt;")
                .replace("\r"," ").replace("\n"," ");
    }
}
