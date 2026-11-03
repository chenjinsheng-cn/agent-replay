package com.chenjinsheng.agentreplay.agent.client;

import com.chenjinsheng.agentreplay.agent.AgentLoopException;
import com.chenjinsheng.agentreplay.agent.LlmClient;
import com.chenjinsheng.agentreplay.agent.model.LlmMessage;
import com.chenjinsheng.agentreplay.agent.model.LlmResponse;
import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线模式：按顺序吐出预先写好的模型回答，一次网络都不发。
 *
 * <p>存在的意义有两个。</p>
 * <ol>
 *   <li><b>clone 下来就能跑。</b>不用申请 key、不用配环境、不怕断网。
 *       面试时别人拿到仓库，{@code mvn spring-boot:run} 就能看到完整时间轴。</li>
 *   <li><b>给测试一个稳定的上游。</b>需要断言「编排逻辑对不对」的测试不应该依赖模型今天的心情，
 *       用固定脚本，测试就变成了确定性的。</li>
 * </ol>
 *
 * <p>注意它和 {@code ReplayLlmClient} 的区别——这是最容易被问混淆的一处：</p>
 * <ul>
 *   <li>{@code RecordedLlmClient}（本类）：<b>模拟</b>一次运行。模型回答是写死的，工具是真的执行的。
 *       用途是「没有 key 也能演示」。</li>
 *   <li>{@code ReplayLlmClient}：<b>还原</b>一次发生过的运行。模型回答和工具结果都来自那次事件的记录，
 *       什么都不执行。用途是「验证我改代码之后行为变没变」。</li>
 * </ul>
 *
 * <p>脚本每次调用都从第一条开始（{@link #reset()}），因为同一个 bean 会被多次运行复用，
 * 不重置的话第二次运行会从上次停下的地方接着吐。</p>
 */
@Slf4j
@Component
public class RecordedLlmClient implements LlmClient {

    private static final String SCRIPT_PATH = "events/demo-script.json";

    private final List<ScriptedResponse> script;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public RecordedLlmClient(ObjectMapper mapper) {
        this.script = load(mapper);
    }

    @Override
    public String mode() {
        return "recorded";
    }

    /** 每次新运行开始前调用，把游标拨回第一条。 */
    public void reset() {
        cursor.set(0);
    }

    /**
     * 把游标往前拨 n 条。
     *
     * <p>分叉时用：新运行的前 n 轮读的是父运行的记录，不该再消耗脚本；
     * 从第 n 条开始接着往下吐才和「从第 n 轮重新生成」的语义对得上。
     * 如果没有这个方法，离线模式下分叉会把前面几轮的脚本又放一遍。</p>
     */
    public void skip(int n) {
        cursor.set(Math.max(0, n));
    }

    @Override
    public LlmResponse complete(List<LlmMessage> messages) {
        int index = cursor.getAndIncrement();
        if (index >= script.size()) {
            // 脚本写了几步就只能走几步。多用一次说明编排出问题了，明确报出来，别拿最后一条糊弄过去
            throw new AgentLoopException(
                    "录制脚本只有 " + script.size() + " 条回答，已被请求第 " + (index + 1) + " 次");
        }
        ScriptedResponse item = script.get(index);
        return new LlmResponse(item.content, item.toolCalls, 0, 0, "recorded-script");
    }

    private List<ScriptedResponse> load(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource(SCRIPT_PATH).getInputStream()) {
            JsonNode root = mapper.readTree(in);
            if (!root.isArray()) {
                throw new IllegalStateException(SCRIPT_PATH + " 顶层应该是数组");
            }
            List<ScriptedResponse> result = new ArrayList<>();
            for (JsonNode node : root) {
                String content = node.path("content").asText("");
                List<ToolCall> calls = new ArrayList<>();
                JsonNode toolCalls = node.path("toolCalls");
                if (toolCalls.isArray()) {
                    for (JsonNode item : toolCalls) {
                        calls.add(new ToolCall(
                                item.path("id").asText(),
                                item.path("name").asText(),
                                toMap(item.path("arguments"), mapper)
                        ));
                    }
                }
                result.add(new ScriptedResponse(content, calls));
            }
            log.info("已加载录制脚本 {}，共 {} 条回答", SCRIPT_PATH, result.size());
            return List.copyOf(result);
        } catch (Exception e) {
            throw new IllegalStateException("读取录制脚本失败：" + SCRIPT_PATH, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node, ObjectMapper mapper) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return mapper.convertValue(node, LinkedHashMap.class);
    }

    private record ScriptedResponse(String content, List<ToolCall> toolCalls) {
    }
}
