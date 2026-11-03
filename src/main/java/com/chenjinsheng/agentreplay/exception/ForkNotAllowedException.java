package com.chenjinsheng.agentreplay.exception;

/** 分叉请求不合法（轮次越界、父运行没跑到那一步），映射成 400。 */
public class ForkNotAllowedException extends RuntimeException {

    public ForkNotAllowedException(String message) {
        super(message);
    }
}
