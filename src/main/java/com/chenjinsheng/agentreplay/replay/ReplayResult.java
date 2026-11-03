package com.chenjinsheng.agentreplay.replay;

import java.util.List;

/**
 * 一次重放的结论。
 *
 * @param consistent   重放算出的摘要和当初存的一致吗
 * @param originalHash 当初记下的摘要
 * @param replayHash   刚才重放算出的摘要
 * @param finalAnswer  重放得到的最终答案
 * @param steps        重放走了几轮
 * @param toolSequence 重放过程中调用的工具名，按顺序
 * @param notes        给人看的说明：一致时说「校验通过」，不一致时说清哪一步开始对不上
 */
public record ReplayResult(
        boolean consistent,
        String originalHash,
        String replayHash,
        String finalAnswer,
        int steps,
        List<String> toolSequence,
        List<String> notes
) {
}
