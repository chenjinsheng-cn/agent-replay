package com.chenjinsheng.agentreplay.replay;

import com.chenjinsheng.agentreplay.agent.AgentLoop;
import com.chenjinsheng.agentreplay.agent.AgentOutcome;
import com.chenjinsheng.agentreplay.agent.model.LlmResponse;
import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.config.AgentProperties;
import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.AgentRun;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.chenjinsheng.agentreplay.support.CollectingSink;
import com.chenjinsheng.agentreplay.support.CountingToolExecutor;
import com.chenjinsheng.agentreplay.support.ScriptedLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReplayEngine：重放校验")
class ReplayEngineTest {

    private ReplayEngine engine;
    private List<AgentEvent> events;
    private AgentRun run;

    /** 先跑一遍真实循环，拿到一份「标准答案」：事件流 + 摘要。 */
    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getLoop().setMaxSteps(8);
        engine = new ReplayEngine(properties, new ObjectMapper());

        ScriptedLlmClient llm = new ScriptedLlmClient(
                new LlmResponse("先算一下", List.of(new ToolCall("c1", "calc", Map.of("expression", "128/4"))), 10, 5, "m"),
                new LlmResponse("结果是 32", List.of(), 12, 6, "m")
        );
        CollectingSink sink = new CollectingSink("run-A");
        AgentOutcome outcome = new AgentLoop(8).run("算 128/4", llm, new CountingToolExecutor(), sink);

        events = new ArrayList<>(sink.events());

        run = new AgentRun();
        run.setId("run-A");
        run.setGoal("算 128/4");
        run.setTranscriptHash(Transcript.hash(outcome.messages()));
    }

    @Test
    @DisplayName("原样重放：摘要一致")
    void consistentReplay() {
        ReplayResult result = engine.replay(run, events);

        assertThat(result.consistent()).isTrue();
        assertThat(result.replayHash()).isEqualTo(run.getTranscriptHash());
        assertThat(result.finalAnswer()).isEqualTo("结果是 32");
        assertThat(result.toolSequence()).containsExactly("calc");
        assertThat(result.notes()).anyMatch(n -> n.contains("摘要一致"));
    }

    @Test
    @DisplayName("篡改工具输出：重放仍然跑得通，但摘要对不上")
    void tamperedToolOutputBreaksHash() {
        events.replaceAll(e -> {
            if (e.getType() == EventType.TOOL_COMPLETED) {
                AgentEvent copy = copyOf(e);
                copy.setPayload(e.getPayload().replace("result-of-calc", "被人改过的结果"));
                return copy;
            }
            return e;
        });

        ReplayResult result = engine.replay(run, events);

        assertThat(result.consistent()).isFalse();
        assertThat(result.notes()).anyMatch(n -> n.contains("摘要不一致"));
    }

    @Test
    @DisplayName("篡改工具名：回放错位，明确报出来")
    void tamperedToolNameIsRejected() {
        events.replaceAll(e -> {
            if (e.getType() == EventType.TOOL_COMPLETED) {
                AgentEvent copy = copyOf(e);
                copy.setPayload(e.getPayload().replace("\"tool\":\"calc\"", "\"tool\":\"write_file\""));
                return copy;
            }
            return e;
        });

        ReplayResult result = engine.replay(run, events);

        assertThat(result.consistent()).isFalse();
        assertThat(result.notes()).anyMatch(n -> n.contains("回放错位"));
    }

    @Test
    @DisplayName("少了模型回答：编排要多请求一次时明确失败")
    void missingLlmResponseIsRejected() {
        // 只保留第一条 LLM_RESPONDED，其它全删——模拟「记录被截断」
        List<AgentEvent> onlyFirst = new ArrayList<>();
        int seen = 0;
        for (AgentEvent e : events) {
            if (e.getType() == EventType.LLM_RESPONDED) {
                if (seen == 0) {
                    onlyFirst.add(e);
                }
                seen++;
            } else {
                onlyFirst.add(e);
            }
        }

        ReplayResult result = engine.replay(run, onlyFirst);

        assertThat(result.consistent()).isFalse();
        assertThat(result.notes()).anyMatch(n -> n.contains("回放"));
    }

    @Test
    @DisplayName("没有摘要的运行（中途失败）：显式说明，不假装通过")
    void runWithoutHash() {
        run.setTranscriptHash(null);

        ReplayResult result = engine.replay(run, events);

        assertThat(result.consistent()).isFalse();
        assertThat(result.notes()).anyMatch(n -> n.contains("没有最终摘要"));
    }

    private AgentEvent copyOf(AgentEvent source) {
        AgentEvent copy = new AgentEvent();
        copy.setId(source.getId());
        copy.setRunId(source.getRunId());
        copy.setSeq(source.getSeq());
        copy.setStep(source.getStep());
        copy.setType(source.getType());
        copy.setPayload(source.getPayload());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }
}
