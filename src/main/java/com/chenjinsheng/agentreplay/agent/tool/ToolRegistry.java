package com.chenjinsheng.agentreplay.agent.tool;

import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：名字到实现的映射。
 *
 * <p>用 {@link LinkedHashMap} 而不是 {@link java.util.HashMap}，是为了让工具列表的顺序稳定。
 * 工具列表会原样发给模型，如果每次启动顺序都变，真实运行就很难做「同样的 prompt」对照实验——
 * 而这个项目的全部意义就是对照。</p>
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> discovered) {
        for (Tool tool : discovered) {
            Tool previous = tools.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("工具名重复：" + tool.name());
            }
        }
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public Tool find(String name) {
        return tools.get(name);
    }

    /** 执行一次调用。工具名不存在时返回错误结果，而不是抛异常。 */
    public ToolResult execute(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error("没有名为 " + call.name() + " 的工具，可用的是：" + tools.keySet());
        }
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        try {
            return tool.execute(args);
        } catch (RuntimeException e) {
            // 工具内部漏抛了：兜住，转成模型能看懂的错误文本，不中断整个运行
            return ToolResult.error("工具 " + call.name() + " 执行失败：" + e.getMessage());
        }
    }
}
