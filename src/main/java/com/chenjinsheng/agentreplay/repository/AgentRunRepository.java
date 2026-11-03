package com.chenjinsheng.agentreplay.repository;

import com.chenjinsheng.agentreplay.domain.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {

    List<AgentRun> findAllByOrderByCreatedAtDesc();

    List<AgentRun> findByParentRunIdOrderByCreatedAtAsc(String parentRunId);
}
