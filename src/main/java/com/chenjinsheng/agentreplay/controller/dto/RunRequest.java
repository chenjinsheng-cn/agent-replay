package com.chenjinsheng.agentreplay.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 发起一次运行。 */
public record RunRequest(
        @NotBlank(message = "goal 不能为空")
        @Size(max = 2000, message = "goal 不能超过 2000 字")
        String goal
) {
}
