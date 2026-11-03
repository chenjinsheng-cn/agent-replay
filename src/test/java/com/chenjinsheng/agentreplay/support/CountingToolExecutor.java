package com.chenjinsheng.agentreplay.support;

import com.chenjinsheng.agentreplay.agent.ToolExecutor;
import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.agent.model.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的工具执行器：记录被调用了几次。
 *
 * <p>回放测试全靠它来断言「一次都没被执行」——这是整个安全性主张的核心。</p>
 */
public class CountingToolExecutor implements ToolExecutor {

    private final List<String> calls = new ArrayList<>();

    @Override
    public ToolResult execute(ToolCall call, int step) {
        calls.add(call.name() + "@" + step);
        return ToolResult.ok("result-of-" + call.name());
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public int count() {
        return calls.size();
    }
}
