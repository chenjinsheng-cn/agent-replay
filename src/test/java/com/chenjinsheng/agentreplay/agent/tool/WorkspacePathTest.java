package com.chenjinsheng.agentreplay.agent.tool;

import com.chenjinsheng.agentreplay.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WorkspacePath：路径穿越防护")
class WorkspacePathTest {

    @TempDir
    Path tempDir;

    private WorkspacePath path() {
        AgentProperties properties = new AgentProperties();
        properties.getFiles().setWorkspace(tempDir);
        return new WorkspacePath(properties);
    }

    @Test
    @DisplayName("普通相对路径正常解析")
    void resolvesNormalPath() {
        Path base = tempDir.toAbsolutePath().normalize();
        assertThat(path().resolve("notes/a.txt")).isEqualTo(base.resolve("notes/a.txt"));
    }

    @Test
    @DisplayName(".. 越界被挡住")
    void blocksParentTraversal() {
        assertThatThrownBy(() -> path().resolve("../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界");
    }

    @Test
    @DisplayName("绕一圈的 .. 也挡得住（先 normalize 再判断）")
    void blocksTrickyTraversal() {
        assertThatThrownBy(() -> path().resolve("notes/../../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界");
    }

    @Test
    @DisplayName("相对路径里的 . 会被规范化掉")
    void normalizesDotSegments() {
        Path base = tempDir.toAbsolutePath().normalize();
        assertThat(path().resolve("./notes/./a.txt")).isEqualTo(base.resolve("notes/a.txt"));
    }

    @Test
    @DisplayName("空路径直接拒绝")
    void rejectsBlank() {
        assertThatThrownBy(() -> path().resolve("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
