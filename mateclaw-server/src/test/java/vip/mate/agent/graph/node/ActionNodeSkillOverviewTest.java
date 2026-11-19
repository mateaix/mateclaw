package vip.mate.agent.graph.node;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.agent.runtime.state.RunState;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;
class ActionNodeSkillOverviewTest {
    private Map<String,Object> run(String question,String args,boolean success) {
        String body="{\"kind\":\"skill_catalog\",\"matchingTotal\":2,\"names\":[\"pdf\",\"xlsx\"],\"summaries\":{\"pdf\":\"Read PDF\",\"xlsx\":\"Read spreadsheet\"},\"hasMore\":false}";
        var call=new AssistantMessage.ToolCall("c","function","listAvailableSkills",args);
        var state=new RunState(Map.of(USER_MESSAGE,question,CONVERSATION_ID,"overview",TOOL_CALLS,List.of(call)));
        var result=new ToolExecutionExecutor.ToolExecutionResult(List.of(new ToolResponseMessage.ToolResponse("c","listAvailableSkills",body)),
            List.of(GraphEventPublisher.toolComplete("c","listAvailableSkills",body,success)),false,null,null);
        return new ActionNode(null).completeBatch(state,result);
    }
    @Test void factualOverviewTerminatesAfterCanonicalReadWithoutAnotherModelCall() {
        var out=run("你有哪些技能？","{}",true);
        assertEquals(true,out.get(RETURN_DIRECT_TRIGGERED));
        var outputs=(List<DirectToolOutput>)out.get(DIRECT_TOOL_OUTPUTS);
        assertEquals(1,outputs.size());assertTrue(outputs.getFirst().fullResult().contains("2"));
        assertTrue(outputs.getFirst().fullResult().contains("pdf"));assertTrue(outputs.getFirst().fullResult().contains("Read spreadsheet"));
    }
    @Test void filteredFailedAndCompoundTasksMustKeepNormalReasoning() {
        for(var q:List.of("你有哪些技能并帮我生成报告","用pdf技能提取文字","请列出全部技能的完整使用文档"))
            assertNotEquals(true,run(q,"{}",true).get(RETURN_DIRECT_TRIGGERED));
        assertNotEquals(true,run("你有哪些技能","{\"keyword\":\"pdf\"}",true).get(RETURN_DIRECT_TRIGGERED));
        assertNotEquals(true,run("你有哪些技能","{}",false).get(RETURN_DIRECT_TRIGGERED));
    }
}
