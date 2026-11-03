package com.chenjinsheng.agentreplay.replay;

import com.chenjinsheng.agentreplay.agent.AgentLoopException;
import com.chenjinsheng.agentreplay.agent.LlmClient;
import com.chenjinsheng.agentreplay.agent.model.LlmMessage;
import com.chenjinsheng.agentreplay.agent.model.LlmResponse;
import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回放用的模型客户端：不请求模型，按顺序吐出当初记下的回答。
 *
 * <p>它只做一件事：把事件流里的 {@code LLM_RESPONDED} 一条条排好，每次 {@code complete()}
 * 取一条。因为 {@link com.chenjinsheng.agentreplay.agent.AgentLoop} 的调用顺序是确定的
 * （第 0 轮、第 1 轮……），只要编排逻辑没变，取到的顺序就和当初一致。</p>
 *
 * <p>如果取不到（回放时请求次数比当初多），说明编排逻辑变了——比如加了「反思」环节
 * 导致模型多问了一轮，或者循环条件写错了。这时必须<b>失败</b>而不是糊弄过去，
 * 因为「重放时报错」正是这个工具想要发出的信号。</p>
 */
public class ReplayLlmClient implements LlmClient {

    private final List<LlmResponse> recorded;
    private final ObjectMapper mapper;
    private int cursor;

    public ReplayLlmClient(List<AgentEvent> events, ObjectMapper mapper) {
        this.mapper = mapper;
        this.recorded = extract(events);
    }

    @Override
    public String mode() {
        return "replay";
    }

    @Override
    public LlmResponse complete(List<LlmMessage> messages) {
        if (cursor >= recorded.size()) {
            throw new AgentLoopException("回放错位：编排请求了第 " + (cursor + 1)
                    + " 次模型，但记录里只有 " + recorded.size() + " 次回答");
        }
        return recorded.get(cursor++);
    }

    /** 这次回放总共会用掉多少次模型回答。 */
    public int recordedCount() {
        return recorded.size();
    }

    private List<LlmResponse> extract(List<AgentEvent> events) {
        List<LlmResponse> result = new ArrayList<>();
        for (AgentEvent event : events) {
            if (event.getType() != EventType.LLM_RESPONDED) {
                continue;
            }
            result.add(parse(event.getPayload()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private LlmResponse parse(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            String content = node.path("content").asText("");
            List<ToolCall> calls = new ArrayList<>();
            JsonNode toolCalls = node.path("toolCalls");
            if (toolCalls.isArray()) {
                for (JsonNode item : toolCalls) {
                    calls.add(new ToolCall(
                            item.path("id").asText(),
                            item.path("name").asText(),
                            item.has("arguments") && item.get("arguments").isObject()
                                    ? mapper.convertValue(item.get("arguments"), LinkedHashMap.class)
                                    : Map.of()
                    ));
                }
            }
            return new LlmResponse(
                    content,
                    calls,
                    node.path("promptTokens").asInt(0),
                    node.path("completionTokens").asInt(0),
                    node.path("model").asText("")
            );
        } catch (Exception e) {
            throw new AgentLoopException("事件 payload 解析失败：" + payload, e);
        }
    }
}
