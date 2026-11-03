package com.chenjinsheng.agentreplay.repository;

import com.chenjinsheng.agentreplay.domain.AgentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentEventRepository extends JpaRepository<AgentEvent, Long> {

    /**
     * 读一次运行的全部事件。
     *
     * <p>永远按 seq 升序——这是重放的唯一合法顺序。数据库层面的插入顺序不可靠，
     * 只有显式 seq 才是权威。</p>
     */
    List<AgentEvent> findByRunIdOrderBySeqAsc(String runId);

    int countByRunId(String runId);
}
