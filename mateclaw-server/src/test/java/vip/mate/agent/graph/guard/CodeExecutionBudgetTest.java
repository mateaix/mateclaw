package vip.mate.agent.graph.guard;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CodeExecutionBudgetTest {
    @Test void negatedQuotedAndConditionalLimitsAreNotRuntimeAuthority() {
        for(String text:new String[]{"不要只调用一次 execute_code，必要时可以多次调用", "如果只调用一次 execute_code 不够，可以继续",
            "解释一下‘只调用一次 execute_code’是什么意思", "只调用一次 execute_code，若不够可以继续调用", "示例代码：\n```text\n只调用一次 execute_code\n```"})
            assertTrue(CodeExecutionBudget.limit(text).isEmpty(),text);
    }
    @Test void standaloneExplicitImperativesAreRecognizedWithoutGuessing() {
        assertEquals(1,CodeExecutionBudget.limit("只调用一次 execute_code，完成聚合。").orElseThrow());
        assertEquals(2,CodeExecutionBudget.limit("先完成准备。必须依次调用两次execute_code。然后报告。").orElseThrow());
        assertEquals(2,CodeExecutionBudget.limit("At most 2 execute_code calls.").orElseThrow());
        assertTrue(CodeExecutionBudget.limit("调用一次或两次execute_code均可").isEmpty());
        assertTrue(CodeExecutionBudget.limit("只调用一次 execute_code。最多调用两次 execute_code。").isEmpty());
    }
    @Test void bridgeCallsShareTheSameCanonicalExecutionBudget() {
        var ledger=vip.mate.agent.graph.state.ActionExecutionLedger.fromEvents(java.util.List.of(
            vip.mate.agent.GraphEventPublisher.toolComplete("used","execute_code","done",true)));
        var call=new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("next","function","tool_call",
            "{\"toolName\":\"execute_code\",\"arguments\":{\"language\":\"python\",\"code\":\"print(1)\"}}");
        var review=CodeExecutionBudget.review(null,null,null,new vip.mate.agent.runtime.state.RunState(),
            "只调用一次 execute_code。",java.util.List.of(call),ledger,"c",16000,0,8);
        assertEquals(CodeExecutionBudget.Decision.BLOCKED,review.decision());assertEquals(0,review.calls());
        var read=new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("read","function","read_file","invalid-json");
        assertEquals(CodeExecutionBudget.Decision.NOT_APPLICABLE,CodeExecutionBudget.review(null,null,null,
            new vip.mate.agent.runtime.state.RunState(),"只调用一次 execute_code。",java.util.List.of(read),ledger,"c",16000,0,8).decision());
    }

}
