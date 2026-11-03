package com.chenjinsheng.agentreplay.agent.model;

/**
 * 工具执行结果。
 *
 * <p>{@code ok=false} 不是异常，是一种正常结果——模型看到「工具报错了」往往能自己纠正
 * （换个参数、换个工具）。所以错误信息要作为文本喂回给模型，而不是抛到外面中断整个运行。</p>
 */
public record ToolResult(boolean ok, String output) {

    public static ToolResult ok(String output) {
        return new ToolResult(true, output);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, message);
    }
}
