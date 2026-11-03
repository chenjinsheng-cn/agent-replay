package com.chenjinsheng.agentreplay.agent.tool;

import com.chenjinsheng.agentreplay.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 列出 workspace 下的文件（只一层，不递归）。
 *
 * <p>不递归是故意的：递归列目录在文件多的时候输出会长到没法看，而且模型真正需要的
 * 通常只是「这一层有什么」。要深入就一层一层点下去，这比一次性甩给它一棵树更容易追踪。</p>
 */
@Component
public class ListFilesTool implements Tool {

    private final WorkspacePath workspacePath;

    public ListFilesTool(WorkspacePath workspacePath) {
        this.workspacePath = workspacePath;
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "列出工作区里某个目录下的文件和子目录（只一层）。不传 path 表示工作区根目录。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "path", Map.of("type", "string", "description", "相对于工作区的目录，可省略")
        ));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object raw = arguments.get("path");
        String relative = raw == null ? "." : raw.toString();
        Path dir;
        try {
            dir = workspacePath.resolve(relative);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.exists(dir)) {
            return ToolResult.error("目录不存在：" + relative);
        }
        if (!Files.isDirectory(dir)) {
            return ToolResult.error(relative + " 不是目录");
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<String> entries = stream
                    .sorted()
                    .map(p -> workspacePath.relativize(p) + (Files.isDirectory(p) ? "/" : ""))
                    .collect(Collectors.toList());
            if (entries.isEmpty()) {
                return ToolResult.ok("（空目录）");
            }
            return ToolResult.ok(String.join("\n", entries));
        } catch (IOException e) {
            return ToolResult.error("列目录失败：" + e.getMessage());
        }
    }
}
