package com.chenjinsheng.agentreplay.replay;

import com.chenjinsheng.agentreplay.agent.AgentLoop;
import com.chenjinsheng.agentreplay.agent.AgentOutcome;
import com.chenjinsheng.agentreplay.agent.EventSink;
import com.chenjinsheng.agentreplay.config.AgentProperties;
import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.AgentRun;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 重放引擎：拿一条已有的事件流，把编排代码重新跑一遍。
 *
 * <p><b>它到底在验证什么？</b>这是面试里最需要说清楚的一句话：</p>
 * <blockquote>
 * 它验证的不是「模型会不会给出同样的答案」——那由录制保证，跟我的代码无关。
 * 它验证的是「<b>面对同样的模型输出，我的编排逻辑会不会做出同样的动作</b>」。
 * </blockquote>
 *
 * <p>举个具体例子：如果把 AgentLoop 里「拿到 tool_calls 就执行」改成「先判断工具名白名单再执行」，
 * 而白名单漏了一个工具，那么重放时第 2 轮的工具序列就会和记录对不上，
 * {@code RecordedToolExecutor} 会直接抛错，摘要也对不上。<b>这个改动上线前就会被发现</b>，
 * 不需要等用户撞见。</p>
 *
 * <p>反过来，如果只是把系统提示词里的措辞改得更客气，消息序列里 system 那一条变了，
 * 摘要也会变，但工具序列不变——这时候「摘要不一致」并不代表代码有 bug，
 * 只代表「输入变了」。所以 {@link ReplayResult#notes()} 会同时给出工具序列，
 * 让人自己判断属于哪一种。</p>
 */
@Slf4j
@Component
public class ReplayEngine {

    private final int maxSteps;
    private final ObjectMapper mapper;

    public ReplayEngine(AgentProperties properties, ObjectMapper mapper) {
        this.maxSteps = properties.getLoop().getMaxSteps();
        this.mapper = mapper;
    }

    public ReplayResult replay(AgentRun run, List<AgentEvent> events) {
        List<String> notes = new ArrayList<>();

        if (run.getTranscriptHash() == null) {
            notes.add("这次运行没有最终摘要（多半是中途失败了），只能回放到出错的位置");
        }

        ReplayLlmClient llm = new ReplayLlmClient(events, mapper);
        RecordedToolExecutor tools = new RecordedToolExecutor(events, mapper);

        AgentLoop loop = new AgentLoop(maxSteps);
        AgentOutcome outcome;
        try {
            outcome = loop.run(run.getGoal(), llm, tools, EventSink.NO_OP);
        } catch (RuntimeException e) {
            notes.add("重放中断：" + e.getMessage());
            return new ReplayResult(false, run.getTranscriptHash(), null, null, 0,
                    toolSequence(events), notes);
        }

        String replayHash = Transcript.hash(outcome.messages());
        boolean consistent = Objects.equals(run.getTranscriptHash(), replayHash);
        List<String> toolSeq = toolSequence(events);

        if (consistent) {
            notes.add("摘要一致：" + Transcript.shortHash(replayHash) + "，编排逻辑与当初完全相同");
        } else {
            notes.add("摘要不一致——原始 " + Transcript.shortHash(run.getTranscriptHash())
                    + "，重放 " + Transcript.shortHash(replayHash));
            notes.add("如果工具序列没变，多半是提示词/上下文变了；如果工具序列变了，就是编排逻辑变了");
        }

        return new ReplayResult(consistent, run.getTranscriptHash(), replayHash,
                outcome.finalAnswer(), outcome.steps(), toolSeq, notes);
    }

    /** 从事件流里抽出工具调用序列，用于展示和对比。 */
    public List<String> toolSequence(List<AgentEvent> events) {
        List<String> result = new ArrayList<>();
        for (AgentEvent event : events) {
            if (event.getType() == EventType.TOOL_COMPLETED) {
                result.add(toolName(event.getPayload()));
            }
        }
        return result;
    }

    private String toolName(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            return node.path("tool").asText("?");
        } catch (Exception e) {
            return "?";
        }
    }
}
