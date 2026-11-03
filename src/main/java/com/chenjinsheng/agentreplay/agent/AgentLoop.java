package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.agent.model.LlmMessage;
import com.chenjinsheng.agentreplay.agent.model.LlmResponse;
import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.agent.model.ToolResult;
import com.chenjinsheng.agentreplay.domain.EventType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 主循环：问模型 → 要不要调工具 → 调 → 把结果喂回去 → 再问。
 *
 * <p><b>这个类里没有任何一行代码知道「现在是真实运行还是重放」。</b>
 * 它拿到的 {@link LlmClient} 和 {@link ToolExecutor} 可能就是回放实现，
 * 但它不需要知道——这正是整个设计的目的。如果重放需要在这里加 if，
 * 那重放校验的东西就和真实运行不是同一份代码了，校验也就失去了意义。</p>
 *
 * <p>循环的终止条件有两个：模型不再要工具（正常结束），或者步数用满（异常结束）。
 * 第二个条件必须存在——模型有时候会陷入「再查一次」的循环，没有上限就是烧钱。</p>
 *
 * <p>关于「一轮」的定义：模型的一次回答算一轮，如果它一口气要调三个工具，
 * 这三个调用属于同一轮（{@code step} 相同）。这样「步数」和「模型请求次数」是同一个数，
 * 事后按 step 分组看时间轴才顺。</p>
 */
public class AgentLoop {

    /**
     * 系统提示词。
     *
     * <p>写得比较硬这几条不是因为模型笨，是因为它们是<b>可验证的行为约束</b>——
     * 重放时如果模型换了策略（比如不再先 list 再 read），消息序列的摘要就会变，
     * 这就成了一次「行为变更」的显式信号，而不是悄悄跑偏。</p>
     */
    static final String SYSTEM_PROMPT = """
            你是一个在工作区里干活的助手，可以通过工具查看文件和做计算。

            规则：
            1. 需要看文件时先 list_files 看目录，再 read_file 读具体文件，不要猜文件名。
            2. 任何算术都用 calc 工具，不要心算。
            3. 拿到足够的信息后，直接用一两句话给出最终答案，不要再多调工具。

            工作区是一个受限目录，你只能访问它下面的文件。
            """;

    private final int maxSteps;

    public AgentLoop(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public AgentLoop() {
        this(8);
    }

    /**
     * 跑一次。
     *
     * @param goal  用户目标
     * @param llm   模型客户端（真实 / 录制 / 回放）
     * @param tools 工具执行器（真实 / 回放）
     * @param sink  事件出口（落库 / 丢弃）
     */
    public AgentOutcome run(String goal, LlmClient llm, ToolExecutor tools, EventSink sink) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(SYSTEM_PROMPT));
        messages.add(LlmMessage.user(goal));

        sink.emit(EventType.RUN_STARTED, -1, Map.of("goal", goal, "mode", llm.mode()));

        for (int step = 0; step < maxSteps; step++) {
            Map<String, Object> requestPayload = new LinkedHashMap<>();
            requestPayload.put("step", step);
            requestPayload.put("mode", llm.mode());
            requestPayload.put("messageCount", messages.size());
            sink.emit(EventType.LLM_REQUESTED, step, requestPayload);

            LlmResponse response = llm.complete(messages);

            Map<String, Object> responsePayload = new LinkedHashMap<>();
            responsePayload.put("step", step);
            responsePayload.put("content", response.content());
            responsePayload.put("toolCalls", describeCalls(response.toolCalls()));
            responsePayload.put("promptTokens", response.promptTokens());
            responsePayload.put("completionTokens", response.completionTokens());
            responsePayload.put("model", response.model());
            sink.emit(EventType.LLM_RESPONDED, step, responsePayload);

            String content = response.content() == null ? "" : response.content();
            messages.add(LlmMessage.assistant(content, response.toolCalls()));

            if (!response.hasToolCalls()) {
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("finalAnswer", content);
                done.put("steps", step + 1);
                sink.emit(EventType.RUN_FINISHED, step, done);
                return new AgentOutcome(content, step + 1, messages);
            }

            for (ToolCall call : response.toolCalls()) {
                Map<String, Object> toolRequest = new LinkedHashMap<>();
                toolRequest.put("step", step);
                toolRequest.put("tool", call.name());
                toolRequest.put("arguments", call.arguments());
                toolRequest.put("idempotencyKey", idempotencyKey(sink.runId(), step, call));
                sink.emit(EventType.TOOL_REQUESTED, step, toolRequest);

                ToolResult result = tools.execute(call, step);

                Map<String, Object> toolDone = new LinkedHashMap<>();
                toolDone.put("step", step);
                toolDone.put("tool", call.name());
                toolDone.put("ok", result.ok());
                toolDone.put("output", result.output());
                sink.emit(EventType.TOOL_COMPLETED, step, toolDone);

                messages.add(LlmMessage.toolResult(call.id(), call.name(), result.output()));
            }
        }

        String error = "超过最大步数 " + maxSteps + " 仍未给出最终答案";
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("error", error);
        failed.put("steps", maxSteps);
        sink.emit(EventType.RUN_FAILED, maxSteps, failed);
        throw new AgentLoopException(error);
    }

    /**
     * 幂等键：一次运行 + 一轮 + 一个调用，三者确定唯一一次工具执行。
     *
     * <p>真实场景里它的用途是「失败重试时不要重复产生副作用」——调用方把这个键带下去，
     * 下游服务用它去重。本项目只负责生成和记录，不做真正的去重。</p>
     */
    private String idempotencyKey(String runId, int step, ToolCall call) {
        return runId + ":" + step + ":" + call.id();
    }

    private List<Map<String, Object>> describeCalls(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> described = new ArrayList<>();
        for (ToolCall call : calls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.id());
            item.put("name", call.name());
            item.put("arguments", call.arguments());
            described.add(item);
        }
        return described;
    }
}
