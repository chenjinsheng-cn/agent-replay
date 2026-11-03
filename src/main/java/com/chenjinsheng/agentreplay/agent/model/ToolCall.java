package com.chenjinsheng.agentreplay.agent.model;

import java.util.Map;

/**
 * 模型要求执行的一次工具调用。
 *
 * @param id        模型给的调用 id，工具结果要带着它回去对号
 * @param name      工具名
 * @param arguments 参数，已从 JSON 解析成 Map
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {
}
