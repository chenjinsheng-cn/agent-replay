package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.chenjinsheng.agentreplay.repository.AgentEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * 把事件逐条落库。
 *
 * <p><b>每写一条就提交一次</b>，不攒到最后统一 flush。理由很实际：
 * 如果进程在第 5 步崩了，前 4 步的事件必须还在——它们正是事后排查的依据。
 * 攒批的话，崩溃就等于全丢，而这个项目的全部价值就是「崩溃之后还能知道发生了什么」。</p>
 *
 * <p>代价是每次运行多几次事务提交。demo 规模下一次运行最多十几个事件，可以接受；
 * 真要做成产品，正确做法是写本地的 append-only 日志文件再异步同步，而不是调大事务范围——
 * 那又回到了「攒批」的老问题上。</p>
 *
 * <p>{@link #seq} 在构造时从已有事件数接上，所以同一个 run 可以分几次继续追加，
 * 序号始终连续不重复。</p>
 */
@Slf4j
public class PersistingEventSink implements EventSink {

    private final String runId;
    private final AgentEventRepository repository;
    private final ObjectMapper mapper;
    private int seq;

    public PersistingEventSink(String runId, AgentEventRepository repository, ObjectMapper mapper) {
        this.runId = runId;
        this.repository = repository;
        this.mapper = mapper;
        this.seq = repository.countByRunId(runId);
    }

    @Override
    public void emit(EventType type, int step, Object payload) {
        AgentEvent event = new AgentEvent();
        event.setRunId(runId);
        event.setSeq(seq++);
        event.setType(type);
        event.setStep(step);
        event.setPayload(toJson(payload));
        event.setCreatedAt(Instant.now());
        repository.save(event);
        log.debug("[{}] #{}\t{}", runId, event.getSeq(), type);
    }

    @Override
    public String runId() {
        return runId;
    }

    private String toJson(Object payload) {
        if (payload == null) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // payload 是我们自己构造的，序列化不了说明代码写错了；但不能因为记日志失败就中断运行
            log.warn("事件 payload 序列化失败 type={} err={}", payload.getClass().getName(), e.getMessage());
            return "{\"error\":\"payload not serializable\"}";
        }
    }
}
