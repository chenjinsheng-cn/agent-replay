package com.chenjinsheng.agentreplay;

import com.chenjinsheng.agentreplay.domain.AgentEvent;
import com.chenjinsheng.agentreplay.domain.AgentRun;
import com.chenjinsheng.agentreplay.domain.EventType;
import com.chenjinsheng.agentreplay.domain.RunStatus;
import com.chenjinsheng.agentreplay.replay.ReplayResult;
import com.chenjinsheng.agentreplay.replay.RunDiff;
import com.chenjinsheng.agentreplay.service.ForkService;
import com.chenjinsheng.agentreplay.service.RunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端：发起运行 → 重放 → 分叉 → 对比。
 *
 * <p>跑在离线模式（application-test.properties 里 {@code agent.llm.mode=recorded}），
 * 不联网，结果每次一样。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("端到端：跑一次、重放、分叉、对比")
class AgentReplayIntegrationTest {

    @Autowired
    private RunService runService;

    @Autowired
    private ForkService forkService;

    @BeforeEach
    void prepareWorkspace() throws IOException {
        Files.createDirectories(Path.of("./target/test-workspace"));
    }

    @Test
    @DisplayName("发起运行：状态 FINISHED、有摘要、事件流完整")
    void startRun() {
        AgentRun run = runService.start("看看工作区，再算一下 (128+64)*3-72/4");

        assertThat(run.getStatus()).isEqualTo(RunStatus.FINISHED);
        assertThat(run.getFinalAnswer()).contains("558");
        assertThat(run.getTranscriptHash()).hasSize(64);
        // RUN_STARTED(1) + 两轮各 4 条(8) + 最后只剩模型问答的 2 条 + RUN_FINISHED(1) = 12
        assertThat(run.getEventCount()).isEqualTo(12);

        List<AgentEvent> events = runService.events(run.getId());
        assertThat(events).extracting(AgentEvent::getType)
                .startsWith(EventType.RUN_STARTED)
                .endsWith(EventType.RUN_FINISHED);
        // seq 连续
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).getSeq()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("重放：摘要一致，且不产生任何新事件")
    void replayIsConsistentAndReadOnly() {
        AgentRun run = runService.start("看看工作区，再算一下 (128+64)*3-72/4");
        int before = runService.events(run.getId()).size();

        ReplayResult result = runService.replay(run.getId());

        assertThat(result.consistent()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo(run.getFinalAnswer());
        assertThat(result.toolSequence()).containsExactly("list_files", "calc");
        assertThat(runService.events(run.getId())).hasSize(before);
    }

    @Test
    @DisplayName("分叉：复制前缀事件，从指定轮次继续")
    void forkCopiesPrefix() {
        AgentRun parent = runService.start("看看工作区，再算一下 (128+64)*3-72/4");

        AgentRun fork = forkService.fork(parent.getId(), 1);

        assertThat(fork.getParentRunId()).isEqualTo(parent.getId());
        assertThat(fork.getForkAtStep()).isEqualTo(1);
        assertThat(fork.getStatus()).isEqualTo(RunStatus.FINISHED);

        List<AgentEvent> parentEvents = runService.events(parent.getId());
        List<AgentEvent> forkEvents = runService.events(fork.getId());

        // 前缀（step < 1）的五条事件必须逐条一致
        List<AgentEvent> parentPrefix = parentEvents.stream().filter(e -> e.getStep() < 1).toList();
        assertThat(forkEvents).hasSizeGreaterThanOrEqualTo(parentPrefix.size());
        for (int i = 0; i < parentPrefix.size(); i++) {
            assertThat(forkEvents.get(i).getType()).isEqualTo(parentPrefix.get(i).getType());
            assertThat(forkEvents.get(i).getPayload()).isEqualTo(parentPrefix.get(i).getPayload());
        }
        assertThat(forkEvents).extracting(AgentEvent::getType).endsWith(EventType.RUN_FINISHED);
    }

    @Test
    @DisplayName("分叉点越界：明确报错，不生成半截运行")
    void forkOutOfRange() {
        AgentRun parent = runService.start("看看工作区，再算一下 (128+64)*3-72/4");

        assertThatThrownBy(() -> forkService.fork(parent.getId(), 99))
                .isInstanceOf(com.chenjinsheng.agentreplay.exception.ForkNotAllowedException.class)
                .hasMessageContaining("越界");
    }

    @Test
    @DisplayName("对比：父运行和分叉的工具序列一致")
    void diffParentAndFork() {
        AgentRun parent = runService.start("看看工作区，再算一下 (128+64)*3-72/4");
        AgentRun fork = forkService.fork(parent.getId(), 1);

        RunDiff diff = runService.diff(parent.getId(), fork.getId());

        assertThat(diff.leftTools()).containsExactly("list_files", "calc");
        assertThat(diff.rightTools()).containsExactly("list_files", "calc");
        assertThat(diff.sameToolSequence()).isTrue();
        assertThat(diff.sameFinalAnswer()).isTrue();
        // 这次分叉什么都没改：除了 mode 标记，两边逐条一致，不该报「从第几条开始分叉」
        assertThat(diff.firstDivergenceSeq()).isNull();
    }
}
