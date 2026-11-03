package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.agent.model.LlmMessage;

import java.util.List;

/**
 * 一次运行跑完之后的结果。
 *
 * @param finalAnswer 模型最后给出的答案
 * @param steps       实际走了几轮
 * @param messages    完整消息序列——重放时拿它的摘要和当初存的比，用来判断行为有没有变
 */
public record AgentOutcome(String finalAnswer, int steps, List<LlmMessage> messages) {
}
