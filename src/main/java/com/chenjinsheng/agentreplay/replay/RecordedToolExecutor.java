package com.chenjinsheng.agentreplay.replay;

import com.chenjinsheng.agentreplay.agent.AgentLoopException;
import com.chenjinsheng.agentreplay.agent.ToolExecutor;
import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.agent.model.ToolResult;
import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 回放用的工具执行器：**不执行任何工具**，只把当初记下的输出交出去。
 *
 * <p>这是整个项目里安全性最要紧的一个类。它内部没有 {@code ToolRegistry}，
 * 拿不到任何 Tool 实例，所以哪怕有人在这个方法里写错逻辑，也不可能真的去写文件。
 * 「重放不产生副作用」不是靠约定，是靠这里<b>没有能力</b>。</p>
 *
 * <p>校验两件事：</p>
 * <ol>
 *   <li>记录里的工具名必须和这次请求的一致——不一致说明编排改了调用顺序，属于行为变更；</li>
 *   <li>步号必须一致——同一轮的调用不能跑到别的轮次去。</li>
 * </ol>
 * <p>两条都过了才返回结果。任何一条对不上，都直接抛 {@link AgentLoopException}，
 * 让“回放失败”这件事本身变成一个可读的信号。</p>
 */
public class RecordedToolExecutor implements ToolExecutor {

    private final List<RecordedCall> recorded;
    private int cursor;

    public RecordedToolExecutor(List<AgentEvent> events, ObjectMapper mapper) {
        this.recorded = extract(events, mapper);
    }

    @Override
    public ToolResult execute(ToolCall call, int step) {
        if (cursor >= recorded.size()) {
            throw new AgentLoopException("回放错位：编排要执行 " + call.name()
                    + "，但记录里只剩 " + recorded.size() + " 次工具调用");
        }
        RecordedCall item = recorded.get(cursor++);
        if (!item.tool.equals(call.name())) {
            throw new AgentLoopException("回放错位：第 " + cursor + " 次工具调用记录的是 "
                    + item.tool + "，编排请求的是 " + call.name());
        }
        if (item.step != step) {
            throw new AgentLoopException("回放错位：记录里 " + item.tool + " 在第 " + item.step
                    + " 轮，编排在第 " + step + " 轮调用了它");
        }
        return new ToolResult(item.ok, item.output);
    }

    private List<RecordedCall> extract(List<AgentEvent> events, ObjectMapper mapper) {
        List<RecordedCall> result = new ArrayList<>();
        for (AgentEvent event : events) {
            if (event.getType() != EventType.TOOL_COMPLETED) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(event.getPayload());
                result.add(new RecordedCall(
                        node.path("tool").asText(""),
                        node.path("step").asInt(-1),
                        node.path("ok").asBoolean(true),
                        node.path("output").asText("")
                ));
            } catch (Exception e) {
                throw new AgentLoopException("工具事件 payload 解析失败：" + event.getPayload(), e);
            }
        }
        return result;
    }

    private record RecordedCall(String tool, int step, boolean ok, String output) {
    }
}
