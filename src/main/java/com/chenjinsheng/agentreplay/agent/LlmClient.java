package com.chenjinsheng.agentreplay.agent;

import com.chenjinsheng.agentreplay.agent.model.LlmMessage;
import com.chenjinsheng.agentreplay.agent.model.LlmResponse;

import java.util.List;

/**
 * 模型调用的抽象。
 *
 * <p>整个项目的可重放性就建立在这个接口上：编排代码（{@link AgentLoop}）只认它，
 * 不知道对面是真的在请求 DeepSeek、还是在读一段写死的 JSON、还是在回放上一次运行的记录。
 * 所以「重放」不需要给 AgentLoop 开任何后门——换个实现就行。</p>
 */
public interface LlmClient {

    /**
     * 请求一次模型回答。
     *
     * @param messages 到目前为止的完整对话（不含工具 schema，schema 由实现自己带上）
     */
    LlmResponse complete(List<LlmMessage> messages);

    /** 给人看的模式名，会写进事件的 payload，方便事后分辨这次数据是哪来的。 */
    String mode();
}
