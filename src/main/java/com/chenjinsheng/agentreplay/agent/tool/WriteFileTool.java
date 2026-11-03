package com.chenjinsheng.agentreplay.agent.tool;

import com.chenjinsheng.agentreplay.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 往 workspace 写一个文本文件。**这是本项目里唯一有副作用的工具。**
 *
 * <p>它存在的意义不是「让 agent 能写文件」，而是给重放提供一个反例：
 * 如果重放时又执行一次 {@code write_file}，磁盘上就会出现第二份写入。
 * 所以重放走的是 {@code RecordedToolExecutor}——直接读当初记下的结果，<b>根本不碰这个类</b>。
 * 面试里这一条最好讲：重放之所以安全，不是因为工具被写成了幂等的，而是因为重放路径上压根没有这个工具。</p>
 *
 * <p>但幂等键仍然要记（{@code TOOL_REQUESTED} 事件里带着）。它解决的是另一个问题：
 * 真实运行过程中，某次工具调用因为网络抖动被判超时，重试时怎么确定「上一次其实没写成功」。
 * 这一层本项目只做了记录和展示，没有做真正的落库去重——demo 规模，说清楚边界比假装做了更重要。</p>
 */
@Component
public class WriteFileTool implements Tool {

    private static final int MAX_CHARS = 20000;

    private final WorkspacePath workspacePath;

    public WriteFileTool(WorkspacePath workspacePath) {
        this.workspacePath = workspacePath;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "把文本写入工作区里的一个文件，文件已存在则覆盖。路径相对于工作区，例如 notes/result.txt。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "path", Map.of("type", "string", "description", "相对于工作区的文件路径"),
                "content", Map.of("type", "string", "description", "要写入的完整内容")
        ));
        schema.put("required", List.of("path", "content"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object rawPath = arguments.get("path");
        Object rawContent = arguments.get("content");
        if (rawPath == null) {
            return ToolResult.error("缺少参数 path");
        }
        if (rawContent == null) {
            return ToolResult.error("缺少参数 content");
        }
        String content = rawContent.toString();
        if (content.length() > MAX_CHARS) {
            return ToolResult.error("内容超过 " + MAX_CHARS + " 字符，请拆分写入");
        }
        Path file;
        try {
            file = workspacePath.resolve(rawPath.toString());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        try {
            workspacePath.ensureExists();
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return ToolResult.ok("已写入 " + workspacePath.relativize(file) + "，共 " + content.length() + " 字符");
        } catch (IOException e) {
            return ToolResult.error("写入失败：" + e.getMessage());
        }
    }
}
