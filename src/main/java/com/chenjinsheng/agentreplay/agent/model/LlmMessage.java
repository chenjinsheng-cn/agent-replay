package com.chenjinsheng.agentreplay.agent.model;

import java.util.List;

/**
 * 发给模型的一条消息。
 *
 * <p>用一个 record 装下四种角色，而不是按角色拆成四个类——因为它们在同一次请求里
 * 是混在一个列表里的，拆开会逼调用方到处做 instanceof。</p>
 *
 * @param role       system / user / assistant / tool
 * @param content    正文；工具结果消息里放工具输出
 * @param toolCalls  assistant 消息里模型要求调用的工具；其它角色为 null
 * @param toolCallId tool 角色回填结果时，要说明这是哪一次调用的结果
 * @param toolName   tool 角色的工具名，便于阅读（协议本身只要求 id）
 */
public record LlmMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName
) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null, null);
    }

    public static LlmMessage assistant(String content, List<ToolCall> toolCalls) {
        return new LlmMessage("assistant", content, toolCalls, null, null);
    }

    public static LlmMessage toolResult(String toolCallId, String toolName, String output) {
        return new LlmMessage("tool", output, null, toolCallId, toolName);
    }
}
