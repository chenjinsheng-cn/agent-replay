package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.agent.model.ToolResult;

/**
 * 工具执行的抽象，和 {@link LlmClient} 是同一个套路。
 *
 * <p>为什么要单独抽一层、而不是让 AgentLoop 直接调 {@code ToolRegistry}：
 * 因为「重放时不能产生副作用」这条约束必须由结构保证，不能靠人记得。
 * 重放时注入的是 {@code RecordedToolExecutor}，它手里只有当初记下的输出文本，
 * 根本没有 {@code ToolRegistry} 的引用——<b>想执行也执行不了</b>。</p>
 *
 * <p>这比「给工具加一个 dryRun 参数」强的地方在于：dryRun 是每个工具自己实现的分支，
 * 加一个新工具就可能漏掉；换实现则是编译期就断掉的路。</p>
 */
public interface ToolExecutor {

    /**
     * 执行一次工具调用。
     *
     * @param call 要执行的调用
     * @param step 当前是第几轮循环，回放实现靠它校验「取到的是不是同一步的结果」
     */
    ToolResult execute(ToolCall call, int step);
}
