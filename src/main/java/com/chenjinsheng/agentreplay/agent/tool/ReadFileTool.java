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
 * 读 workspace 下的一个文本文件。
 *
 * <p>截断到 {@value #MAX_CHARS} 字符：工具输出会原样进入下一次模型请求的上下文，
 * 一个几 MB 的日志文件能直接把上下文撑爆。截断了总比请求失败好，而且会明确告诉模型
 * 「后面还有内容没给你看」，它自己会决定要不要分段读。</p>
 */
@Component
public class ReadFileTool implements Tool {

    private static final int MAX_CHARS = 4000;

    private final WorkspacePath workspacePath;

    public ReadFileTool(WorkspacePath workspacePath) {
        this.workspacePath = workspacePath;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取工作区里的一个文本文件。路径相对于工作区，例如 notes/todo.txt。超过 4000 字符会被截断。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "path", Map.of("type", "string", "description", "相对于工作区的文件路径")
        ));
        schema.put("required", List.of("path"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object raw = arguments.get("path");
        if (raw == null) {
            return ToolResult.error("缺少参数 path");
        }
        Path file;
        try {
            file = workspacePath.resolve(raw.toString());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.exists(file)) {
            return ToolResult.error("文件不存在：" + raw);
        }
        if (Files.isDirectory(file)) {
            return ToolResult.error(raw + " 是一个目录，用 list_files 查看");
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > MAX_CHARS) {
                return ToolResult.ok(content.substring(0, MAX_CHARS)
                        + "\n...（已截断，原文件共 " + content.length() + " 字符）");
            }
            return ToolResult.ok(content);
        } catch (IOException e) {
            return ToolResult.error("读取失败：" + e.getMessage());
        }
    }
}
