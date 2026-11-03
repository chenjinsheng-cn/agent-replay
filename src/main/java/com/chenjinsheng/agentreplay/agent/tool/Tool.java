package com.chenjinsheng.agentreplay.agent.tool;

import com.chenjinsheng.agentreplay.agent.model.ToolResult;

import java.util.Map;

/**
 * 一个可被模型调用的工具。
 *
 * <p>接口刻意做得极窄：名字、描述、参数 schema、执行。没有 {@code onStart/onEnd} 钩子，
 * 也没有「是否只读」的标记——因为这些信息如果放在工具自己身上，就意味着<b>工具在替调用方做决策</b>。
 * 回放时到底要不要真的执行，应该由外层（LlmClient 的录制/真实、ToolExecutor 的真实/回放）
 * 决定，而不是问工具「你想被真跑吗」。工具保持无状态，重放才干净。</p>
 */
public interface Tool {

    String name();

    /** 给模型看的自然语言说明。写清楚「什么时候该用它」，比写清参数更重要。 */
    String description();

    /** JSON Schema 形式的参数定义。 */
    Map<String, Object> parameters();

    /**
     * 执行。
     *
     * <p>约定：<b>工具不抛异常</b>。任何失败都包成 {@link ToolResult#error(String)} 返回，
     * 让模型看见错误并自己纠正。真的抛出运行时异常，会被 AgentLoop 记成 RUN_FAILED，
     * 整个运行直接结束——对「参数写错了」这种可恢复问题来说太重了。</p>
     */
    ToolResult execute(Map<String, Object> arguments);
}
