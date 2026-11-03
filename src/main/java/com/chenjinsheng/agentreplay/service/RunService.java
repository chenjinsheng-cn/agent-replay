package com.chenjinsheng.agentreplay.service;

import com.chenjinsheng.agentreplay.agent.AgentLoop;
import com.chenjinsheng.agentreplay.agent.AgentOutcome;
import com.chenjinsheng.agentreplay.agent.EventSink;
import com.chenjinsheng.agentreplay.agent.LlmClient;
import com.chenjinsheng.agentreplay.agent.PersistingEventSink;
import com.chenjinsheng.agentreplay.agent.RealToolExecutor;
import com.chenjinsheng.agentreplay.agent.ToolExecutor;
import com.chenjinsheng.agentreplay.agent.client.DeepSeekLlmClient;
import com.chenjinsheng.agentreplay.agent.client.RecordedLlmClient;
import com.chenjinsheng.agentreplay.config.AgentProperties;
import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.AgentRun;
import com.chenjinsheng.agentreplay.domain.RunStatus;
import com.chenjinsheng.agentreplay.exception.RunNotFoundException;
import com.chenjinsheng.agentreplay.replay.ReplayEngine;
import com.chenjinsheng.agentreplay.replay.ReplayResult;
import com.chenjinsheng.agentreplay.replay.RunDiff;
import com.chenjinsheng.agentreplay.replay.RunDiffer;
import com.chenjinsheng.agentreplay.replay.Transcript;
import com.chenjinsheng.agentreplay.repository.AgentEventRepository;
import com.chenjinsheng.agentreplay.repository.AgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 运行的门面：建 run、跑循环、存结果、查历史。
 *
 * <p><b>这里刻意不加 {@code @Transactional}。</b>加了的话，整个方法会共用同一个事务，
 * 事件就要等到方法结束才真正落库——那样进程中途崩掉，最需要留下的那部分日志正好丢了。
 * 现在每条事件由 Spring Data 的 {@code save()} 各自提交，代价是运行期间多几次事务，
 * 换来的是「崩溃前发生的事一定查得到」。</p>
 */
@Slf4j
@Service
public class RunService {

    private final AgentRunRepository runRepository;
    private final AgentEventRepository eventRepository;
    private final DeepSeekLlmClient realLlm;
    private final RecordedLlmClient recordedLlm;
    private final RealToolExecutor realToolExecutor;
    private final ReplayEngine replayEngine;
    private final RunDiffer runDiffer;
    private final AgentProperties properties;
    private final ObjectMapper mapper;

    public RunService(AgentRunRepository runRepository,
                      AgentEventRepository eventRepository,
                      DeepSeekLlmClient realLlm,
                      RecordedLlmClient recordedLlm,
                      RealToolExecutor realToolExecutor,
                      ReplayEngine replayEngine,
                      RunDiffer runDiffer,
                      AgentProperties properties,
                      ObjectMapper mapper) {
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.realLlm = realLlm;
        this.recordedLlm = recordedLlm;
        this.realToolExecutor = realToolExecutor;
        this.replayEngine = replayEngine;
        this.runDiffer = runDiffer;
        this.properties = properties;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    public AgentRun start(String goal) {
        AgentRun run = newRun(goal, null, null);
        runRepository.save(run);

        LlmClient llm = selectLlm();
        AgentLoop loop = new AgentLoop(properties.getLoop().getMaxSteps());
        EventSink sink = new PersistingEventSink(run.getId(), eventRepository, mapper);

        execute(run, goal, llm, realToolExecutor, sink, loop);
        return run;
    }

    /**
     * 跑一次循环并把结果写回 run。
     * 分叉那边复用这一段——它和新建运行唯一的区别是传入的 client/executor/sink 不同。
     */
    public void execute(AgentRun run, String goal, LlmClient llm, ToolExecutor tools,
                        EventSink sink, AgentLoop loop) {
        run.setStatus(RunStatus.RUNNING);
        try {
            AgentOutcome outcome = loop.run(goal, llm, tools, sink);
            run.setStatus(RunStatus.FINISHED);
            run.setFinalAnswer(outcome.finalAnswer());
            run.setTranscriptHash(Transcript.hash(outcome.messages()));
        } catch (RuntimeException e) {
            // 运行失败不是 HTTP 500：它是一次「有记录可查的失败」，状态和时间轴都留着
            log.warn("运行 {} 失败：{}", run.getId(), e.getMessage());
            run.setStatus(RunStatus.FAILED);
            if (run.getFinalAnswer() == null) {
                run.setFinalAnswer(null);
            }
        }
        run.setFinishedAt(Instant.now());
        run.setEventCount(eventRepository.countByRunId(run.getId()));
        runRepository.save(run);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public AgentRun getOrThrow(String id) {
        return runRepository.findById(id)
                .orElseThrow(() -> new RunNotFoundException(id));
    }

    public List<AgentRun> list() {
        return runRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<AgentEvent> events(String id) {
        getOrThrow(id);
        return eventRepository.findByRunIdOrderBySeqAsc(id);
    }

    public List<AgentRun> children(String id) {
        return runRepository.findByParentRunIdOrderByCreatedAtAsc(id);
    }

    public ReplayResult replay(String id) {
        AgentRun run = getOrThrow(id);
        List<AgentEvent> events = eventRepository.findByRunIdOrderBySeqAsc(id);
        return replayEngine.replay(run, events);
    }

    public RunDiff diff(String leftId, String rightId) {
        AgentRun left = getOrThrow(leftId);
        AgentRun right = getOrThrow(rightId);
        return runDiffer.diff(left, eventRepository.findByRunIdOrderBySeqAsc(leftId),
                right, eventRepository.findByRunIdOrderBySeqAsc(rightId));
    }

    // ------------------------------------------------------------------
    // 供 ForkService 复用
    // ------------------------------------------------------------------

    public LlmClient selectLlm() {
        if (properties.getLlm().isReal()) {
            return realLlm;
        }
        recordedLlm.reset();
        return recordedLlm;
    }

    public ToolExecutor realTools() {
        return realToolExecutor;
    }

    public EventSink newSink(String runId) {
        return new PersistingEventSink(runId, eventRepository, mapper);
    }

    public AgentLoop newLoop() {
        return new AgentLoop(properties.getLoop().getMaxSteps());
    }

    public AgentRun newRun(String goal, String parentRunId, Integer forkAtStep) {
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setStatus(RunStatus.PENDING);
        run.setGoal(goal);
        run.setParentRunId(parentRunId);
        run.setForkAtStep(forkAtStep);
        run.setLlmMode(properties.getLlm().isReal() ? "real" : "recorded");
        run.setEventCount(0);
        run.setCreatedAt(Instant.now());
        return run;
    }

    public AgentRunRepository runRepository() {
        return runRepository;
    }

    public AgentEventRepository eventRepository() {
        return eventRepository;
    }
}
