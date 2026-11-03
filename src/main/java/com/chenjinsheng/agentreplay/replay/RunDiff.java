package com.chenjinsheng.agentreplay.replay;

import java.util.List;

/**
 * 两次运行的对比结果。
 *
 * @param leftId              左边的运行
 * @param rightId             右边的运行
 * @param leftAnswer          左边的最终答案
 * @param rightAnswer         右边的最终答案
 * @param leftSteps           左边走了几轮
 * @param rightSteps          右边走了几轮
 * @param leftTools           左边的工具调用序列
 * @param rightTools          右边的工具调用序列
 * @param sameToolSequence    工具序列是否完全一致
 * @param sameFinalAnswer     最终答案是否完全一致
 * @param firstDivergenceSeq  第几条事件开始不一样；null 表示前缀完全相同
 * @param firstDivergenceNote 分歧点的可读描述
 */
public record RunDiff(
        String leftId,
        String rightId,
        String leftAnswer,
        String rightAnswer,
        int leftSteps,
        int rightSteps,
        List<String> leftTools,
        List<String> rightTools,
        boolean sameToolSequence,
        boolean sameFinalAnswer,
        Integer firstDivergenceSeq,
        String firstDivergenceNote
) {
}
