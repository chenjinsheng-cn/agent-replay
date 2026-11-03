package com.chenjinsheng.agentreplay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 事件流里的一条。**只追加，不修改，不删除。**
 *
 * <p>（表名不叫 {@code event}——那是 H2 的保留字。）</p>
 *
 * <p>唯一约束 {@code (run_id, seq)} 是整个项目的地基：它保证同一次运行里序号不可能重复。
 * 有了这条约束，重放时才能断言「我读到的第 n 条一定就是当初写的第 n 条」。
 * 如果允许重号，重放就成了猜谜。</p>
 *
 * <p>{@link #getPayload()} 是一段 JSON 字符串，不是映射出来的列。
 * 为什么不做成一张宽表（把 tool_name、output、tokens 都变成列）：
 * 事件类型以后一定会增加，每加一种就改表结构，迁移成本跟着涨；
 * 存成 JSON 则新类型直接写，旧数据原样可读。代价是没法按 payload 里的字段建索引——
 * 但本项目的查询模式永远是「按 run 取全部事件」，不需要那种索引。</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "agent_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_run_seq", columnNames = {"run_id", "seq"}),
        indexes = @Index(name = "idx_event_run", columnList = "run_id, seq")
)
public class AgentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    /**
     * 运行内序号，从 0 开始。
     * 它不是全局自增 id：全局 id 会因为别的运行插入而跳号，跳号的事件流没法拿来判断
     * 「第 3 步到第 4 步之间有没有漏东西」。所以序号必须在 run 内连续。
     */
    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EventType type;

    /** 这条事件属于第几轮循环（0 起）。RUN_STARTED 记为 -1。 */
    @Column(nullable = false)
    private int step;

    @Column(nullable = false, length = 16000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
