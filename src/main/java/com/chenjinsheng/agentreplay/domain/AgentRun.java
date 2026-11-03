package com.chenjinsheng.agentreplay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 一次 agent 运行。
 *
 * <p>这张表存的是「这次运行的元信息」，真正的内容在 {@link AgentEvent} 里。
 * 这里刻意不存消息列表、不存中间状态——那些都能从事件流算出来。
 * 唯一一个「算不出来的」是 {@link #getTranscriptHash()}：它是当时跑完后对完整
 * 消息序列取的摘要，重放时拿来比对，用来证明「同样的输入，编排逻辑给出的结果一模一样」。</p>
 *
 * <p>分叉关系用 {@link #getParentRunId()} + {@link #getForkAtStep()} 表示。
 * 分叉出来的 run 是<b>独立的一份</b>，事件从头复制，不引用父 run 的事件——
 * 这样查一次运行只需要扫一张表，代价是存储冗余。demo 规模下这个取舍是划算的，
 * 真正要用的时候可以改成「父链 + 物化」。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "agent_run")
public class AgentRun {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status;

    /** 用户给的目标。分叉时沿用它，除非调用方显式覆盖。 */
    @Column(nullable = false, length = 2000)
    private String goal;

    @Column(name = "final_answer", length = 8000)
    private String finalAnswer;

    /**
     * 完整消息序列的 SHA-256。
     * 重放时会重新算出同一个值来比对——对不上就说明编排代码和当初跑的不一样了。
     */
    @Column(name = "transcript_hash", length = 64)
    private String transcriptHash;

    /** 分叉来源，null 表示这是一次原始运行。 */
    @Column(name = "parent_run_id", length = 36)
    private String parentRunId;

    /**
     * 分叉点：保留父运行的第 0 .. forkAtStep-1 轮，从第 forkAtStep 轮开始重新生成。
     *
     * <p>按「轮次」而不是「事件序号」切，是因为事件序号可能落在一轮中间
     * （比如已经记了 LLM_RESPONDED、还没记 TOOL_COMPLETED）。从中间切，
     * 重建出来的状态是半个循环，没法接着跑。轮次边界才是天然的对齐点。</p>
     */
    @Column(name = "fork_at_step")
    private Integer forkAtStep;

    /** 这次运行用的模型模式（real / recorded），重放时用来提醒「不是同一种来源」。 */
    @Column(name = "llm_mode", length = 16)
    private String llmMode;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 事件总数，供列表页展示；由 {@link #eventCount} 维护，不额外 count。 */
    public boolean isTerminal() {
        return status == RunStatus.FINISHED || status == RunStatus.FAILED;
    }
}
