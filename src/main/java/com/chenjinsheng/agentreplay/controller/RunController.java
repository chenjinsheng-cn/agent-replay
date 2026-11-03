package com.chenjinsheng.agentreplay.controller;

import com.chenjinsheng.agentreplay.controller.dto.EventView;
import com.chenjinsheng.agentreplay.controller.dto.RunRequest;
import com.chenjinsheng.agentreplay.controller.dto.RunView;
import com.chenjinsheng.agentreplay.domain.AgentRun;
import com.chenjinsheng.agentreplay.replay.ReplayResult;
import com.chenjinsheng.agentreplay.replay.RunDiff;
import com.chenjinsheng.agentreplay.service.ForkService;
import com.chenjinsheng.agentreplay.service.RunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对外接口。
 *
 * <p>路径设计上只有两个动作是「写」的：发起运行和分叉。其余全是读——
 * 重放、对比、看事件都不改变任何状态。这一点值得在面试里点出来：
 * <b>调试工具越只读越好用</b>，因为可以放心地反复点。</p>
 */
@RestController
@RequestMapping("/api/runs")
@Tag(name = "agent 运行", description = "发起运行、看时间轴、重放校验、分叉对比")
public class RunController {

    private final RunService runService;
    private final ForkService forkService;
    private final ObjectMapper mapper;

    public RunController(RunService runService, ForkService forkService, ObjectMapper mapper) {
        this.runService = runService;
        this.forkService = forkService;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(summary = "发起一次运行", description = "按当前 agent.llm.mode 选择真实或录制模式")
    public RunView start(@Valid @RequestBody RunRequest request) {
        AgentRun run = runService.start(request.goal());
        return RunView.of(run);
    }

    @GetMapping
    @Operation(summary = "运行列表", description = "按创建时间倒序")
    public List<RunView> list() {
        return runService.list().stream().map(RunView::of).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "运行详情")
    public RunView detail(@PathVariable String id) {
        return RunView.of(runService.getOrThrow(id));
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "完整事件流", description = "按 seq 升序，payload 已解析成 JSON")
    public List<EventView> events(@PathVariable String id) {
        return runService.events(id).stream()
                .map(e -> EventView.of(e, mapper))
                .toList();
    }

    @GetMapping("/{id}/replay")
    @Operation(summary = "重放校验", description = "不请求模型、不执行工具，只重跑编排逻辑并比对摘要")
    public ReplayResult replay(@PathVariable String id) {
        return runService.replay(id);
    }

    @GetMapping("/{id}/children")
    @Operation(summary = "由这次运行分叉出来的运行")
    public List<RunView> children(@PathVariable String id) {
        return runService.children(id).stream().map(RunView::of).toList();
    }

    @PostMapping("/{id}/fork")
    @Operation(summary = "分叉", description = "保留前 atStep 轮，从这一轮开始重新生成")
    public RunView fork(@PathVariable String id, @RequestParam int atStep) {
        return RunView.of(forkService.fork(id, atStep));
    }

    @GetMapping("/diff")
    @Operation(summary = "对比两次运行", description = "给出工具序列差异和第一个分歧点")
    public RunDiff diff(@RequestParam String left, @RequestParam String right) {
        return runService.diff(left, right);
    }
}
