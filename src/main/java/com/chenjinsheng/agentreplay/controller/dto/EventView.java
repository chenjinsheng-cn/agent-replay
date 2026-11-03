package com.chenjinsheng.agentreplay.controller.dto;

import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * 一条事件的对外形状。
 *
 * <p>{@code payload} 解析成 JSON 对象再返回，而不是原样给一段转义过的字符串。
 * 差别很大：字符串形式在 curl 里是一堆 {@code \"}，人根本读不下去；
 * 解析后是缩进好的结构，时间轴直接可读。<b>调试工具的输出必须能被人直接看，否则等于没有。</b></p>
 */
public record EventView(
        int seq,
        EventType type,
        int step,
        JsonNode payload,
        Instant createdAt
) {

    public static EventView of(AgentEvent event, ObjectMapper mapper) {
        JsonNode node;
        try {
            node = mapper.readTree(event.getPayload());
        } catch (Exception e) {
            // payload 坏了也要能看见这一条，而不是整个接口 500
            node = mapper.createObjectNode().put("raw", event.getPayload());
        }
        return new EventView(event.getSeq(), event.getType(), event.getStep(), node, event.getCreatedAt());
    }
}
