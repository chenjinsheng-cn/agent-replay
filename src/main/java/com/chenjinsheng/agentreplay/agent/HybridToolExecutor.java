package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.agent.model.ToolResult;

/**
 * 和 {@link HybridLlmClient} 配套：前 N 次工具调用走回放，之后真的执行。
 *
 * <p>两个 hybrid 必须用同一个 N。如果只 hybrid 了模型而工具全真跑，
 * 分叉时前几轮的工具会被<b>再执行一遍</b>——{@code write_file} 就会写第二次。
 * 这一条是重放安全性里最容易漏的地方，所以两个类刻意做成一对、参数同源。</p>
 */
public class HybridToolExecutor implements ToolExecutor {

    private final ToolExecutor prefixSource;
    private final ToolExecutor delegate;
    private final int prefixCalls;
    private int used;

    public HybridToolExecutor(ToolExecutor prefixSource, ToolExecutor delegate, int prefixCalls) {
        this.prefixSource = prefixSource;
        this.delegate = delegate;
        this.prefixCalls = prefixCalls;
    }

    @Override
    public ToolResult execute(ToolCall call, int step) {
        if (used < prefixCalls) {
            used++;
            return prefixSource.execute(call, step);
        }
        return delegate.execute(call, step);
    }
}
