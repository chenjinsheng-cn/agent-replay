package com.chenjinsheng.agentreplay.replay;

import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.AgentRun;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RunDiffer：两次运行的差异定位")
class RunDifferTest {

    private final RunDiffer differ = new RunDiffer(new ObjectMapper());

    @Test
    @DisplayName("完全相同的两条事件流：不报分叉")
    void identicalStreamsHaveNoDivergence() {
        List<AgentEvent> events = sampleStream("recorded");

        RunDiff diff = differ.diff(run("a", "558"), events, run("b", "558"), copyOf(events));

        assertThat(diff.firstDivergenceSeq()).isNull();
        assertThat(diff.sameToolSequence()).isTrue();
        assertThat(diff.sameFinalAnswer()).isTrue();
    }

    @Test
    @DisplayName("只有 mode 不同：不算分叉（这条是实跑撞出来的）")
    void modeOnlyDifferenceIsNotDivergence() {
        // 分叉出来的运行：前缀复制的 mode 是 recorded，后半段走混合客户端记成 fork(recorded)。
        // 两边工具、参数、输出逐字相同，只是「谁答的」这个标记变了。
        List<AgentEvent> parent = sampleStream("recorded");
        List<AgentEvent> fork = sampleStream("fork(recorded)");

        RunDiff diff = differ.diff(run("a", "558"), parent, run("b", "558"), fork);

        assertThat(diff.firstDivergenceSeq())
                .as("mode 只是元数据，不该把行为差异淹掉")
                .isNull();
        assertThat(diff.sameToolSequence()).isTrue();
    }

    @Test
    @DisplayName("只有 idempotencyKey 不同：不算分叉（里面带着 runId，必然不同）")
    void idempotencyKeyIsNotDivergence() {
        List<AgentEvent> left = new ArrayList<>();
        left.add(event(0, 0, EventType.TOOL_REQUESTED,
                "{\"step\":0,\"tool\":\"calc\",\"arguments\":{},\"idempotencyKey\":\"run-a:0:c1\"}"));
        List<AgentEvent> right = new ArrayList<>();
        right.add(event(0, 0, EventType.TOOL_REQUESTED,
                "{\"step\":0,\"tool\":\"calc\",\"arguments\":{},\"idempotencyKey\":\"run-b:0:c1\"}"));

        RunDiff diff = differ.diff(run("a", null), left, run("b", null), right);

        assertThat(diff.firstDivergenceSeq()).isNull();
    }

    @Test
    @DisplayName("工具参数变了：这个必须报（去掉的只是元数据，参数是行为）")
    void argumentsDifferenceIsStillReported() {
        List<AgentEvent> left = new ArrayList<>();
        left.add(event(0, 0, EventType.TOOL_REQUESTED,
                "{\"step\":0,\"tool\":\"calc\",\"arguments\":{\"expression\":\"1+1\"},\"idempotencyKey\":\"x:0:c\"}"));
        List<AgentEvent> right = new ArrayList<>();
        right.add(event(0, 0, EventType.TOOL_REQUESTED,
                "{\"step\":0,\"tool\":\"calc\",\"arguments\":{\"expression\":\"1+2\"},\"idempotencyKey\":\"x:0:c\"}"));

        RunDiff diff = differ.diff(run("a", null), left, run("b", null), right);

        assertThat(diff.firstDivergenceSeq()).isEqualTo(0);
        assertThat(diff.firstDivergenceNote()).contains("1+2");
    }

    @Test
    @DisplayName("工具输出被改：在该条上报出来，note 带上原始 payload")
    void toolOutputDifferenceIsReported() {
        List<AgentEvent> left = sampleStream("recorded");
        List<AgentEvent> right = sampleStream("recorded");
        right.set(3, event(3, 1, EventType.TOOL_COMPLETED,
                "{\"step\":1,\"tool\":\"calc\",\"ok\":true,\"output\":\"999\"}"));

        RunDiff diff = differ.diff(run("a", "558"), left, run("b", "999"), right);

        assertThat(diff.firstDivergenceSeq()).isEqualTo(3);
        assertThat(diff.firstDivergenceNote()).contains("第 3 条").contains("999");
        assertThat(diff.sameFinalAnswer()).isFalse();
    }

    @Test
    @DisplayName("一边短一截：从短的那条之后开始报，并且说清两边各剩多少")
    void lengthMismatchIsReported() {
        List<AgentEvent> left = sampleStream("recorded");
        List<AgentEvent> right = new ArrayList<>(left.subList(0, 3));

        RunDiff diff = differ.diff(run("a", "558"), left, run("b", null), right);

        assertThat(diff.firstDivergenceSeq()).isEqualTo(3);
        assertThat(diff.firstDivergenceNote()).contains("还有 2 条").contains("0 条");
    }

    @Test
    @DisplayName("payload 不是 JSON：退回按原文比，不漏报")
    void unparsablePayloadFallsBackToRawCompare() {
        List<AgentEvent> left = List.of(event(0, 0, EventType.RUN_STARTED, "not-json-a"));
        List<AgentEvent> right = List.of(event(0, 0, EventType.RUN_STARTED, "not-json-b"));

        RunDiff diff = differ.diff(run("a", null), left, run("b", null), right);

        assertThat(diff.firstDivergenceSeq()).isEqualTo(0);
    }

    // ---------------------------------------------------------------

    /** 一条最小的、真实形状的事件流：5 条。 */
    private List<AgentEvent> sampleStream(String mode) {
        List<AgentEvent> events = new ArrayList<>();
        events.add(event(0, 0, EventType.LLM_REQUESTED,
                "{\"step\":0,\"mode\":\"" + mode + "\",\"messageCount\":2}"));
        events.add(event(1, 0, EventType.LLM_RESPONDED,
                "{\"step\":0,\"mode\":\"" + mode + "\",\"content\":\"算一下\"}"));
        events.add(event(2, 1, EventType.LLM_REQUESTED,
                "{\"step\":1,\"mode\":\"" + mode + "\",\"messageCount\":4}"));
        events.add(event(3, 1, EventType.TOOL_COMPLETED,
                "{\"step\":1,\"tool\":\"calc\",\"ok\":true,\"output\":\"558\"}"));
        events.add(event(4, 2, EventType.RUN_FINISHED,
                "{\"finalAnswer\":\"558\",\"steps\":2}"));
        return events;
    }

    private List<AgentEvent> copyOf(List<AgentEvent> source) {
        List<AgentEvent> copy = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            AgentEvent e = source.get(i);
            copy.add(event(i, e.getStep(), e.getType(), e.getPayload()));
        }
        return copy;
    }

    private AgentEvent event(int seq, int step, EventType type, String payload) {
        AgentEvent event = new AgentEvent();
        event.setSeq(seq);
        event.setStep(step);
        event.setType(type);
        event.setPayload(payload);
        return event;
    }

    private AgentRun run(String id, String finalAnswer) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setFinalAnswer(finalAnswer);
        return run;
    }
}
