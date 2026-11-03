package com.chenjinsheng.agentreplay.support;

import com.chenjinsheng.agentreplay.agent.LlmClient;
import com.chenjinsheng.agentreplay.agent.model.LlmMessage;
import com.chenjinsheng.agentreplay.agent.model.LlmResponse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** 测试用：按顺序吐出预先排好的回答，用完就报错。 */
public class ScriptedLlmClient implements LlmClient {

    private final Deque<LlmResponse> queue = new ArrayDeque<>();
    private int calls;

    public ScriptedLlmClient(LlmResponse... responses) {
        queue.addAll(List.of(responses));
    }

    @Override
    public String mode() {
        return "scripted";
    }

    @Override
    public LlmResponse complete(List<LlmMessage> messages) {
        calls++;
        if (queue.isEmpty()) {
            throw new IllegalStateException("脚本已用完，被调用第 " + calls + " 次");
        }
        return queue.poll();
    }

    public int calls() {
        return calls;
    }
}
