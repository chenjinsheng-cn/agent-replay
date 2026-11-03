package com.chenjinsheng.agentreplay.service;

import com.chenjinsheng.agentreplay.agent.EventSink;
import com.chenjinsheng.agentreplay.agent.GatedEventSink;
import com.chenjinsheng.agentreplay.agent.HybridLlmClient;
import com.chenjinsheng.agentreplay.agent.HybridToolExecutor;
import com.chenjinsheng.agentreplay.agent.LlmClient;
import com.chenjinsheng.agentreplay.agent.ToolExecutor;
import com.chenjinsheng.agentreplay.agent.client.RecordedLlmClient;
import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.AgentRun;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.chenjinsheng.agentreplay.exception.ForkNotAllowedException;
import com.chenjinsheng.agentreplay.replay.RecordedToolExecutor;
import com.chenjinsheng.agentreplay.replay.ReplayLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 分叉：保留前 N 轮，从第 N 轮开始重新生成。
 *
 * <p>用途是「如果当时模型选了另一个工具，后面会怎样」。做法是把父运行前 N 轮的事件
 * <b>原样复制</b>到新 run，然后让 AgentLoop 从头跑一遍——前 N 轮吃的是复制的数据
 * （不请求模型、不执行工具），从第 N 轮起才回到真实世界。</p>
 *
 * <p><b>为什么按轮次切、不按事件序号切：</b>事件序号可能落在一轮中间
 * （例如已经记了 LLM_RESPONDED、还没记 TOOL_COMPLETED）。从中间切出来的是半个循环，
 * 接着往下跑的状态是残缺的。轮次边界才是天然可续的地方。</p>
 *
 * <p><b>为什么复制事件而不是引用父事件：</b>引用的话，查一次运行要顺着父链往上爬，
 * 而且父运行以后如果被删，子运行就残缺了。复制让每次运行自成一体，
 * 代价是存储翻倍——demo 规模下完全可以接受。</p>
 */
@Slf4j
@Service
public class ForkService {

    private final RunService runService;
    private final ObjectMapper mapper;

    public ForkService(RunService runService, ObjectMapper mapper) {
        this.runService = runService;
        this.mapper = mapper;
    }

    public AgentRun fork(String parentRunId, int atStep) {
        AgentRun parent = runService.getOrThrow(parentRunId);
        List<AgentEvent> parentEvents = runService.eventRepository().findByRunIdOrderBySeqAsc(parentRunId);

        int maxStep = maxStep(parentEvents) + 1;
        if (atStep < 0 || atStep > maxStep) {
            throw new ForkNotAllowedException(
                    "分叉点 " + atStep + " 越界：父运行实际走了 " + maxStep + " 轮，可选范围 0 ~ " + maxStep);
        }

        // 复制「第 atStep 轮之前」的全部事件。RUN_STARTED 的 step 是 -1，天然落在这里面
        List<AgentEvent> prefix = parentEvents.stream()
                .filter(e -> e.getStep() < atStep)
                .toList();

        AgentRun fork = runService.newRun(parent.getGoal(), parent.getId(), atStep);
        runService.runRepository().save(fork);
        copyEvents(prefix, fork.getId());

        int prefixLlmCalls = count(prefix, EventType.LLM_RESPONDED);
        int prefixToolCalls = count(prefix, EventType.TOOL_COMPLETED);

        LlmClient prefixLlm = new ReplayLlmClient(prefix, mapper);
        ToolExecutor prefixTools = new RecordedToolExecutor(prefix, mapper);

        LlmClient delegate = runService.selectLlm();
        if (delegate instanceof RecordedLlmClient recorded) {
            // 离线模式的脚本是一个固定序列：前 n 轮已经从父运行的记录里拿到了，
            // 游标必须跟着拨过去，否则会把前面几轮的脚本再放一遍
            recorded.skip(prefixLlmCalls);
        }

        LlmClient hybridLlm = new HybridLlmClient(prefixLlm, delegate, prefixLlmCalls);
        ToolExecutor hybridTools = new HybridToolExecutor(prefixTools, runService.realTools(), prefixToolCalls);

        // 前缀事件已经复制过了，AgentLoop 重跑这一段时产生的同名事件直接丢掉
        GatedEventSink gated = new GatedEventSink(runService.newSink(fork.getId()), prefix.size());

        runService.execute(fork, parent.getGoal(), hybridLlm, hybridTools, gated, runService.newLoop());

        if (gated.remaining() != 0) {
            // 不抛异常：运行已经跑完了，结果有效。但这是个信号，说明前缀长度和实际事件数对不上
            log.warn("分叉 {} 的事件拦截没有完全对齐，还剩 {} 条未跳过", fork.getId(), gated.remaining());
        }
        return fork;
    }

    private void copyEvents(List<AgentEvent> source, String targetRunId) {
        for (AgentEvent original : source) {
            AgentEvent copy = new AgentEvent();
            copy.setRunId(targetRunId);
            copy.setSeq(original.getSeq());
            copy.setType(original.getType());
            copy.setStep(original.getStep());
            copy.setPayload(original.getPayload());
            copy.setCreatedAt(Instant.now());
            runService.eventRepository().save(copy);
        }
    }

    private int count(List<AgentEvent> events, EventType type) {
        return (int) events.stream().filter(e -> e.getType() == type).count();
    }

    private int maxStep(List<AgentEvent> events) {
        int max = -1;
        for (AgentEvent event : events) {
            max = Math.max(max, event.getStep());
        }
        return max;
    }
}
