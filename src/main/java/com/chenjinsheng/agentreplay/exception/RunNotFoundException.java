package com.chenjinsheng.agentreplay.exception;

/** 运行不存在，映射成 404。 */
public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(String runId) {
        super("运行不存在：" + runId);
    }
}
