package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.domain.EventType;

/**
 * 前 N 次写入直接丢掉，之后才转给真正的 sink。
 *
 * <p>用在分叉上：分叉时已经把父运行前 N 轮的事件<b>复制</b>到新 run 了，
 * 现在 AgentLoop 又要把那一段重跑一遍，会再 emit 一遍同样的事件。
 * 不拦的话就会出现重号（唯一约束直接报错）或者重复历史。</p>
 *
 * <p>为什么用「数 emit 次数」而不是「比较 seq」：seq 由 sink 自己维护，
 * 这里拿不到；而 emit 次数和事件数是一一对应的，数次数就能精确对齐。
 * 对齐的前提是「同一条代码路径产生同样数量的事件」——这本来就是重放的前提，
 * 不成立的话后面的校验本来也会失败。</p>
 */
public class GatedEventSink implements EventSink {

    private final EventSink delegate;
    private int remaining;

    public GatedEventSink(EventSink delegate, int skip) {
        this.delegate = delegate;
        this.remaining = Math.max(0, skip);
    }

    @Override
    public void emit(EventType type, int step, Object payload) {
        if (remaining > 0) {
            remaining--;
            return;
        }
        delegate.emit(type, step, payload);
    }

    @Override
    public String runId() {
        return delegate.runId();
    }

    /** 还有多少条没被跳过。跑完应该正好是 0，否则说明前缀长度估错了。 */
    public int remaining() {
        return remaining;
    }
}
