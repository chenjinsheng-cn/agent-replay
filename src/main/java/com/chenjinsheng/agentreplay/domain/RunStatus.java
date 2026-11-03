package com.chenjinsheng.agentreplay.domain;

/**
 * 一次运行的生命周期。
 *
 * <p>刻意做得很少：没有「暂停」「恢复」这种要额外维护中间状态的值。
 * 一次运行要么还在跑，要么已经落到终态；中断就一律记 FAILED。
 * 分叉产生的是一次<b>新的</b>运行，不是在旧运行上改状态——旧事件流永远不动。</p>
 */
public enum RunStatus {

    /** 已经建了 run 记录，还没开始写事件。 */
    PENDING,

    /** 正在跑，事件在持续追加。 */
    RUNNING,

    /** 正常结束，{@code finalAnswer} 有值。 */
    FINISHED,

    /** 出错结束（模型报错、工具抛异常、步数超限）。 */
    FAILED
}
