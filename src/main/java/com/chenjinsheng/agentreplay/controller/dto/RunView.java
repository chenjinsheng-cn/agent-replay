package com.chenjinsheng.agentreplay.controller.dto;

import com.chenjinsheng.agentreplay.domain.AgentRun;
import com.chenjinsheng.agentreplay.domain.RunStatus;

import java.time.Instant;

/**
 * 返回给外部的运行视图。
 *
 * <p>不直接返回 JPA 实体，跟短链那边是同一个理由：实体字段会随存储细节变化，
 * 而接口契约一旦发出去就不好改。这里顺手把 {@code transcriptHash} 截短到 12 位——
 * 完整的 64 位在 curl 输出里会占满一行，看不出别的。</p>
 */
public record RunView(
        String id,
        RunStatus status,
        String goal,
        String finalAnswer,
        String transcriptHash,
        String parentRunId,
        Integer forkAtStep,
        String llmMode,
        int eventCount,
        Instant createdAt,
        Instant finishedAt
) {

    public static RunView of(AgentRun run) {
        String hash = run.getTranscriptHash();
        if (hash != null && hash.length() > 12) {
            hash = hash.substring(0, 12);
        }
        return new RunView(
                run.getId(),
                run.getStatus(),
                run.getGoal(),
                run.getFinalAnswer(),
                hash,
                run.getParentRunId(),
                run.getForkAtStep(),
                run.getLlmMode(),
                run.getEventCount(),
                run.getCreatedAt(),
                run.getFinishedAt()
        );
    }
}
