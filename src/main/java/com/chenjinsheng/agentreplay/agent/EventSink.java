package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.domain.EventType;

/**
 * 事件写入口。
 *
 * <p>真实运行注入 {@code PersistingEventSink}（写库）；重放注入 {@link #NO_OP}。
 * 重放为什么不写事件：重放是对<b>已有事件流</b>的解释，解释的过程本身不该再变成一份新历史。
 * 想留下「某次重放得到什么结论」，那是要显式创建一次 run 的事，不是副作用。</p>
 */
public interface EventSink {

    void emit(EventType type, int step, Object payload);

    /** 这条事件流属于哪个 run，用于生成工具调用的幂等键。 */
    String runId();

    EventSink NO_OP = new EventSink() {
        @Override
        public void emit(EventType type, int step, Object payload) {
            // 重放：丢弃
        }

        @Override
        public String runId() {
            return "replay";
        }
    };
}
