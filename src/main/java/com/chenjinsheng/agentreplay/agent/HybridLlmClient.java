package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.agent.model.LlmMessage;
import com.chenjinsheng.agentreplay.agent.model.LlmResponse;

import java.util.List;

/**
 * 前 {@code prefixCalls} 次用回放，之后切到真实。
 *
 * <p>分叉就是靠它实现的：新运行的前 N 轮和父运行一模一样（读父运行的记录），
 * 从第 N 轮开始才真去请求模型。<b>这样分叉和原运行用的是同一份 AgentLoop 代码</b>，
 * 不需要给「从中途继续」写第二条执行路径——两条路径迟早会走偏。</p>
 *
 * <p>代价是分叉时会白白重跑一遍前 N 轮的编排逻辑（模型不请求，但循环要转）。
 * N 只有个位数，这点开销换来的是「只有一条代码路径」，值得。</p>
 */
public class HybridLlmClient implements LlmClient {

    private final LlmClient prefixSource;
    private final LlmClient delegate;
    private final int prefixCalls;
    private int used;

    public HybridLlmClient(LlmClient prefixSource, LlmClient delegate, int prefixCalls) {
        this.prefixSource = prefixSource;
        this.delegate = delegate;
        this.prefixCalls = prefixCalls;
    }

    @Override
    public String mode() {
        return "fork(" + delegate.mode() + ")";
    }

    @Override
    public LlmResponse complete(List<LlmMessage> messages) {
        if (used < prefixCalls) {
            used++;
            return prefixSource.complete(messages);
        }
        return delegate.complete(messages);
    }
}
