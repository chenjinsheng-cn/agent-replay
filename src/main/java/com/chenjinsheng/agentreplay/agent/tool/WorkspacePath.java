package com.chenjinsheng.agentreplay.agent.tool;

import com.chenjinsheng.agentreplay.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把模型给的相对路径解析成 workspace 下的真实路径，并挡住路径穿越。
 *
 * <p>这里是整个项目里唯一一处「信任边界」：模型给的字符串可以直接变成磁盘路径。
 * 所以校验不是「contains("..")」这种字符串判断——那种写法挡不住 {@code a/../../etc}、
 * 也挡不住符号链接。正确做法是<b>先 normalize 成绝对路径，再判断是不是以 workspace 开头</b>，
 * 这跟 Spring 的 {@code PathResource} 用的是同一个思路。</p>
 */
@Component
public class WorkspacePath {

    private final Path base;

    public WorkspacePath(AgentProperties properties) {
        this.base = properties.getFiles().getWorkspace().toAbsolutePath().normalize();
    }

    /**
     * 启动就把工作区建出来。
     *
     * <p>不建的话，clone 下来第一次问「看看工作区里有什么」，{@code list_files} 会回
     * 「目录不存在：.」——一个看起来像 bug 的正常结果。目录没了就当场建不出来，
     * 说明整个配置是坏的，那就早点失败，不要拖到模型调用工具时才报。</p>
     */
    @PostConstruct
    void init() {
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建工作区目录：" + base, e);
        }
    }

    public Path base() {
        return base;
    }

    /** 确保 workspace 目录存在。第一次写文件前调用。 */
    public void ensureExists() throws IOException {
        Files.createDirectories(base);
    }

    /**
     * 解析相对路径。
     *
     * @throws IllegalArgumentException 路径越界（指向 workspace 之外）
     */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("路径越界：" + relative + " 不在 workspace 内");
        }
        return target;
    }

    /** 转成展示用的相对路径，避免把本机绝对路径暴露给模型和 API。 */
    public String relativize(Path path) {
        return base.relativize(path).toString().replace('\\', '/');
    }
}
