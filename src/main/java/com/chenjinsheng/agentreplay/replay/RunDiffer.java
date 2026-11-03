package com.chenjinsheng.agentreplay.replay;

import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.AgentRun;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 对比两次运行。
 *
 * <p>分叉出来的运行和父运行共享一段前缀，所以「从第几条开始不一样」是最有信息量的一个数——
 * 它直接告诉你：<b>改动的影响从哪一步开始显现</b>。如果一改就从第 0 条开始不一样，
 * 说明改的是系统提示词或起始上下文；如果是跑到第 6 条才分叉，说明改的是某个中途的判断。</p>
 *
 * <p>比较的内容是「事件类型 + 轮次 + payload」。不比较时间戳和数据库 id——
 * 那两样必然不同，比了只会把真实差异淹没。payload 里的 {@code mode} 字段也排除，
 * 理由见 {@link #semanticPayload}。</p>
 */
@Component
public class RunDiffer {

    private final ObjectMapper mapper;

    public RunDiffer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RunDiff diff(AgentRun left, List<AgentEvent> leftEvents,
                        AgentRun right, List<AgentEvent> rightEvents) {
        Integer divergenceSeq = null;
        String divergenceNote = null;

        int common = Math.min(leftEvents.size(), rightEvents.size());
        for (int i = 0; i < common; i++) {
            if (!sameEvent(leftEvents.get(i), rightEvents.get(i))) {
                divergenceSeq = leftEvents.get(i).getSeq();
                divergenceNote = describe(leftEvents.get(i), rightEvents.get(i));
                break;
            }
        }
        if (divergenceSeq == null && leftEvents.size() != rightEvents.size()) {
            divergenceSeq = common;
            divergenceNote = "前缀相同，但从第 " + common + " 条之后一边还有 " + (leftEvents.size() - common)
                    + " 条、另一边还有 " + (rightEvents.size() - common) + " 条";
        }

        List<String> leftTools = toolNames(leftEvents);
        List<String> rightTools = toolNames(rightEvents);

        return new RunDiff(
                left.getId(),
                right.getId(),
                left.getFinalAnswer(),
                right.getFinalAnswer(),
                countSteps(leftEvents),
                countSteps(rightEvents),
                leftTools,
                rightTools,
                Objects.equals(leftTools, rightTools),
                Objects.equals(left.getFinalAnswer(), right.getFinalAnswer()),
                divergenceSeq,
                divergenceNote
        );
    }

    private boolean sameEvent(AgentEvent a, AgentEvent b) {
        return a.getType() == b.getType()
                && a.getStep() == b.getStep()
                && Objects.equals(semanticPayload(a), semanticPayload(b));
    }

    /** 逐条比对时忽略的字段：它们天然每次都不同，跟「行为有没有变」无关。 */
    private static final List<String> PER_RUN_FIELDS = List.of("mode", "idempotencyKey");

    /**
     * 判分叉用的 payload：去掉只跟「这一次运行」绑定的字段再比。
     *
     * <ul>
     *   <li>{@code mode} —— 哪一步由哪个客户端答的（recorded / real / fork(recorded)）。
     *       分叉前半段是复制的、后半段走混合客户端，所以后半段必然和父运行不同。</li>
     *   <li>{@code idempotencyKey} —— 生成规则是 {@code runId:step:callId}，<b>里面就带着 runId</b>，
     *       两个不同的运行永远不可能相等。</li>
     * </ul>
     *
     * <p>这两样都是元数据。拿原始字符串比的话，一个「什么都没改的分叉」也会报出
     * 「第 5 条开始分叉」（实测：mode 那条），或者「第 7 条开始分叉」（实测：idempotencyKey 那条）——
     * 恰恰把真正要看的「<b>从哪一步开始行为不同</b>」淹掉。</p>
     *
     * <p>代价是：payload 解析不了就退回按原文比（宁可多报差异，也不漏报）。
     * note 里仍然打印原始 payload，真要查的时候看得见。</p>
     */
    private String semanticPayload(AgentEvent event) {
        String payload = event.getPayload();
        if (payload == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(payload);
            if (node instanceof ObjectNode object) {
                PER_RUN_FIELDS.forEach(object::remove);
            }
            return node.toString();
        } catch (JsonProcessingException e) {
            return payload;
        }
    }

    private String describe(AgentEvent left, AgentEvent right) {
        return "第 " + left.getSeq() + " 条：左边是 " + brief(left) + "，右边是 " + brief(right);
    }

    private String brief(AgentEvent event) {
        String payload = event.getPayload();
        if (payload != null && payload.length() > 120) {
            payload = payload.substring(0, 120) + "...";
        }
        return event.getType() + " " + event.getStep() + " " + payload;
    }

    private List<String> toolNames(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> e.getType() == EventType.TOOL_COMPLETED)
                .map(this::toolName)
                .toList();
    }

    private String toolName(AgentEvent event) {
        String payload = event.getPayload();
        int idx = payload.indexOf("\"tool\":\"");
        if (idx < 0) {
            return "?";
        }
        int start = idx + "\"tool\":\"".length();
        int end = payload.indexOf('"', start);
        return end < 0 ? "?" : payload.substring(start, end);
    }

    /** 轮次 = 最后一次 LLM_REQUESTED 的 step + 1；没有请求就是 0。 */
    private int countSteps(List<AgentEvent> events) {
        int max = -1;
        for (AgentEvent event : events) {
            if (event.getType() == EventType.LLM_REQUESTED) {
                max = Math.max(max, event.getStep());
            }
        }
        return max + 1;
    }
}
