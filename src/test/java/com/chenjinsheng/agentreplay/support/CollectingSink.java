package com.chenjinsheng.agentreplay.support;

import com.chenjinsheng.agentreplay.agent.EventSink;
import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 把事件收在内存里的 sink。
 *
 * <p>用它就不需要起数据库，纯逻辑测试能跑得很快。</p>
 */
public class CollectingSink implements EventSink {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String runId;
    private final List<AgentEvent> events = new ArrayList<>();

    public CollectingSink(String runId) {
        this.runId = runId;
    }

    @Override
    public void emit(EventType type, int step, Object payload) {
        AgentEvent event = new AgentEvent();
        event.setRunId(runId);
        event.setSeq(events.size());
        event.setType(type);
        event.setStep(step);
        try {
            event.setPayload(MAPPER.writeValueAsString(payload == null ? java.util.Map.of() : payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        event.setCreatedAt(Instant.now());
        events.add(event);
    }

    @Override
    public String runId() {
        return runId;
    }

    public List<AgentEvent> events() {
        return List.copyOf(events);
    }
}
