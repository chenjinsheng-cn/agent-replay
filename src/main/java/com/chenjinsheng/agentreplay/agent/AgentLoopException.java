package com.chenjinsheng.agentreplay.agent;

/** 运行本身失败（步数超限、模型报错等），会被记成 RUN_FAILED 而不是 HTTP 500。 */
public class AgentLoopException extends RuntimeException {

    public AgentLoopException(String message) {
        super(message);
    }

    public AgentLoopException(String message, Throwable cause) {
        super(message, cause);
    }
}
