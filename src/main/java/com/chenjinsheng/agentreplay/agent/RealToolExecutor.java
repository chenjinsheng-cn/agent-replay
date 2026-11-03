package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.agent.model.ToolResult;
import com.chenjinsheng.agentreplay.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

/** 真实执行：转到 {@link ToolRegistry}，工具真的会读文件、真的会写文件。 */
@Component
public class RealToolExecutor implements ToolExecutor {

    private final ToolRegistry registry;

    public RealToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolResult execute(ToolCall call, int step) {
        return registry.execute(call);
    }
}
