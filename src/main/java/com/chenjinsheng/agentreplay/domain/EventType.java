package com.chenjinsheng.agentreplay.domain;

/**
 * 事件流的六种事件。
 *
 * <p>事件类型的设计只有一条原则：<b>凡是「外部世界给出的回答」，都要单独成事件并原样存下来。</b>
 * 模型的输出、工具的返回值都属于这一类——它们是重放时不能重新获取的东西
 * （模型会变，工具可能有副作用）。而我自己的编排代码不存，因为重放就是要让它重新跑一遍。</p>
 *
 * <p>所以这里每一对 request/response 里，真正被重放消费的是 response 那一半；
 * request 那一半存在的意义是让时间轴可读、以及在 diff 里能看出「当时问的是什么」。</p>
 */
public enum EventType {

    /** 运行开始。payload: {@code {"goal": "..."}} */
    RUN_STARTED,

    /** 即将请求模型。payload: {@code {"step":n,"model":"...","messageCount":n}} */
    LLM_REQUESTED,

    /** 模型返回。payload: {@code {"step":n,"content":"...","toolCalls":[...],"promptTokens":n}} */
    LLM_RESPONDED,

    /** 即将执行工具。payload: {@code {"step":n,"tool":"...","arguments":{...},"idempotencyKey":"..."}} */
    TOOL_REQUESTED,

    /** 工具执行完成。payload: {@code {"step":n,"ok":true,"output":"..."}} */
    TOOL_COMPLETED,

    /** 运行正常结束。payload: {@code {"finalAnswer":"...","steps":n}} */
    RUN_FINISHED,

    /** 运行失败。payload: {@code {"error":"...","steps":n}} */
    RUN_FAILED
}
