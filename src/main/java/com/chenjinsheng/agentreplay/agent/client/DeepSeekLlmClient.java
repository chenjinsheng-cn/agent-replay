package com.chenjinsheng.agentreplay.agent.client;

import com.chenjinsheng.agentreplay.agent.AgentLoopException;
import com.chenjinsheng.agentreplay.agent.LlmClient;
import com.chenjinsheng.agentreplay.agent.model.LlmMessage;
import com.chenjinsheng.agentreplay.agent.model.LlmResponse;
import com.chenjinsheng.agentreplay.agent.model.ToolCall;
import com.chenjinsheng.agentreplay.agent.tool.Tool;
import com.chenjinsheng.agentreplay.agent.tool.ToolRegistry;
import com.chenjinsheng.agentreplay.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真的去请求 DeepSeek。
 *
 * <p>DeepSeek 用的是 OpenAI 兼容协议，所以消息格式、工具（function calling）格式、
 * 返回结构都是通用的那套——换成任何一家兼容 OpenAI 的服务，只需要改 {@code base-url} 和 {@code model}。</p>
 *
 * <p>这里用 {@link RestClient} 而不是 WebClient：整条链路都是同步阻塞的（AgentLoop 就是顺序执行），
 * 上响应式的唯一后果是每个调用点都包一层 {@code block()}，白白多一层心智负担。</p>
 *
 * <p>key 从配置读，配置里的值来自环境变量 {@code DEEPSEEK_API_KEY}。
 * 这个类<b>不打印 key</b>，报错信息里也不会带——日志有可能被贴到别处去。</p>
 */
@Component
public class DeepSeekLlmClient implements LlmClient {

    private final AgentProperties.Llm config;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;
    private final RestClient http;

    public DeepSeekLlmClient(AgentProperties properties, ToolRegistry toolRegistry, ObjectMapper mapper) {
        this.config = properties.getLlm();
        this.toolRegistry = toolRegistry;
        this.mapper = mapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) config.getTimeout().toMillis());
        factory.setReadTimeout((int) config.getTimeout().toMillis());

        this.http = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .build();
    }

    @Override
    public String mode() {
        return "real";
    }

    @Override
    public LlmResponse complete(List<LlmMessage> messages) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new AgentLoopException("real 模式需要环境变量 DEEPSEEK_API_KEY，当前为空");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("temperature", config.getTemperature());
        body.set("messages", toWire(messages));
        ArrayNode toolSpecs = toolSpecs();
        if (!toolSpecs.isEmpty()) {
            body.set("tools", toolSpecs);
            body.put("tool_choice", "auto");
        }

        String raw;
        try {
            raw = http.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // 只截前 300 字：完整响应体可能很长，而真正有用的错误码和 message 都在开头
            String detail = e.getResponseBodyAsString();
            if (detail != null && detail.length() > 300) {
                detail = detail.substring(0, 300) + "...";
            }
            throw new AgentLoopException("模型接口返回 " + e.getStatusCode().value() + "：" + detail, e);
        } catch (RuntimeException e) {
            throw new AgentLoopException("请求模型失败：" + e.getMessage(), e);
        }

        return parse(raw);
    }

    private LlmResponse parse(String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw new AgentLoopException("模型返回的不是合法 JSON：" + head(raw), e);
        }

        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new AgentLoopException("模型返回里没有 choices：" + head(raw));
        }
        JsonNode message = choice.path("message");

        String content = message.hasNonNull("content") ? message.get("content").asText() : "";
        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode item : toolCalls) {
                String id = item.path("id").asText();
                JsonNode fn = item.path("function");
                String name = fn.path("name").asText();
                Map<String, Object> args = parseArguments(fn.path("arguments").asText());
                calls.add(new ToolCall(id, name, args));
            }
        }

        JsonNode usage = root.path("usage");
        return new LlmResponse(
                content,
                calls,
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                root.path("model").asText(config.getModel())
        );
    }

    /**
     * 模型的 arguments 是一段<b>字符串</b>，里面才是 JSON。解析失败时给空参数继续走，
     * 让工具自己报「缺少参数」——比起在这里抛异常整个运行失败，让模型看到错误再纠正更好。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ArrayNode toWire(List<LlmMessage> messages) {
        ArrayNode array = mapper.createArrayNode();
        for (LlmMessage msg : messages) {
            ObjectNode node = array.addObject();
            node.put("role", msg.role());
            if ("assistant".equals(msg.role()) && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                node.put("content", msg.content() == null ? "" : msg.content());
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : msg.toolCalls()) {
                    ObjectNode item = calls.addObject();
                    item.put("id", call.id());
                    item.put("type", "function");
                    ObjectNode fn = item.putObject("function");
                    fn.put("name", call.name());
                    fn.put("arguments", writeArguments(call.arguments()));
                }
            } else if ("tool".equals(msg.role())) {
                node.put("content", msg.content());
                node.put("tool_call_id", msg.toolCallId());
                if (msg.toolName() != null) {
                    node.put("name", msg.toolName());
                }
            } else {
                node.put("content", msg.content() == null ? "" : msg.content());
            }
        }
        return array;
    }

    private String writeArguments(Map<String, Object> arguments) {
        try {
            return mapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ArrayNode toolSpecs() {
        ArrayNode array = mapper.createArrayNode();
        for (Tool tool : toolRegistry.all()) {
            ObjectNode item = array.addObject();
            item.put("type", "function");
            ObjectNode fn = item.putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", mapper.valueToTree(tool.parameters()));
        }
        return array;
    }

    private String head(String raw) {
        if (raw == null) {
            return "(空)";
        }
        return raw.length() > 300 ? raw.substring(0, 300) + "..." : raw;
    }
}
