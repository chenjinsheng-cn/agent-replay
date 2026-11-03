package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.agent.model.LlmResponse;
import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.chenjinsheng.agentreplay.support.CollectingSink;
import com.chenjinsheng.agentreplay.support.CountingToolExecutor;
import com.chenjinsheng.agentreplay.support.ScriptedLlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentLoop：ReAct 主循环")
class AgentLoopTest {

    private static LlmResponse toolCall(String id, String name, Map<String, Object> args) {
        return new LlmResponse("", List.of(new ToolCall(id, name, args)), 10, 5, "test-model");
    }

    private static LlmResponse answer(String content) {
        return new LlmResponse(content, List.of(), 20, 8, "test-model");
    }

    @Test
    @DisplayName("一轮工具调用后收尾：事件顺序固定")
    void oneToolRoundThenFinish() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                toolCall("c1", "calc", Map.of("expression", "1+1")),
                answer("结果是 2")
        );
        CountingToolExecutor tools = new CountingToolExecutor();
        CollectingSink sink = new CollectingSink("run-1");

        AgentOutcome outcome = new AgentLoop(8).run("算一下 1+1", llm, tools, sink);

        assertThat(outcome.finalAnswer()).isEqualTo("结果是 2");
        assertThat(outcome.steps()).isEqualTo(2);
        assertThat(tools.calls()).containsExactly("calc@0");

        assertThat(sink.events()).extracting(AgentEvent::getType).containsExactly(
                EventType.RUN_STARTED,
                EventType.LLM_REQUESTED, EventType.LLM_RESPONDED,
                EventType.TOOL_REQUESTED, EventType.TOOL_COMPLETED,
                EventType.LLM_REQUESTED, EventType.LLM_RESPONDED,
                EventType.RUN_FINISHED
        );

        // seq 必须从 0 连续递增，中间不能有洞
        for (int i = 0; i < sink.events().size(); i++) {
            assertThat(sink.events().get(i).getSeq()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("同一轮的多个工具调用共享同一个 step")
    void parallelToolCallsShareStep() {
        LlmResponse twoCalls = new LlmResponse("", List.of(
                new ToolCall("c1", "calc", Map.of("expression", "1+1")),
                new ToolCall("c2", "calc", Map.of("expression", "2+2"))
        ), 10, 5, "test-model");

        ScriptedLlmClient llm = new ScriptedLlmClient(twoCalls, answer("好了"));
        CollectingSink sink = new CollectingSink("run-2");

        new AgentLoop(8).run("算两个", llm, new CountingToolExecutor(), sink);

        List<AgentEvent> toolEvents = sink.events().stream()
                .filter(e -> e.getType() == EventType.TOOL_COMPLETED)
                .toList();
        assertThat(toolEvents).hasSize(2);
        assertThat(toolEvents).allMatch(e -> e.getStep() == 0);
    }

    @Test
    @DisplayName("模型一直要工具时，步数用满就失败并记 RUN_FAILED")
    void stopsAtMaxSteps() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                toolCall("c1", "calc", Map.of()),
                toolCall("c2", "calc", Map.of()),
                toolCall("c3", "calc", Map.of())
        );
        CollectingSink sink = new CollectingSink("run-3");

        assertThatThrownBy(() -> new AgentLoop(3).run("死循环", llm, new CountingToolExecutor(), sink))
                .isInstanceOf(AgentLoopException.class)
                .hasMessageContaining("最大步数");

        assertThat(sink.events()).extracting(AgentEvent::getType)
                .last().isEqualTo(EventType.RUN_FAILED);
    }

    @Test
    @DisplayName("幂等键 = runId + step + callId")
    void idempotencyKey() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                toolCall("call_abc", "calc", Map.of("expression", "1+1")),
                answer("好")
        );
        CollectingSink sink = new CollectingSink("run-xyz");

        new AgentLoop(8).run("x", llm, new CountingToolExecutor(), sink);

        String payload = sink.events().stream()
                .filter(e -> e.getType() == EventType.TOOL_REQUESTED)
                .findFirst().orElseThrow()
                .getPayload();
        assertThat(payload).contains("run-xyz:0:call_abc");
    }
}
