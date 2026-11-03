package com.chenjinsheng.agentreplay.agent.model;

import java.util.List;

/**
 * 模型的一次回答。
 *
 * <p>{@code toolCalls} 非空表示「它还想调工具」，空表示「它认为可以收尾了」——
 * AgentLoop 就是靠这一点决定继续循环还是结束。</p>
 *
 * <p>token 数一并存进事件里，因为它是成本回溯的唯一依据：
 * 事后想算「这次运行花了多少钱」，重放是算不出来的（重放不请求模型），只能读当时的记录。</p>
 */
public record LlmResponse(
        String content,
        List<ToolCall> toolCalls,
        int promptTokens,
        int completionTokens,
        String model
) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
