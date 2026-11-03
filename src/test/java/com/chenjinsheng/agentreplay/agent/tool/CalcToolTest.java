package com.chenjinsheng.agentreplay.agent.tool;

import com.chenjinsheng.agentreplay.agent.model.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("calc 工具：递归下降解析器")
class CalcToolTest {

    private final CalcTool tool = new CalcTool();

    private ToolResult calc(String expression) {
        return tool.execute(Map.of("expression", expression));
    }

    @Test
    @DisplayName("加减乘除按优先级")
    void respectsPrecedence() {
        assertThat(calc("1 + 2 * 3").output()).isEqualTo("7");
        assertThat(calc("(1 + 2) * 3").output()).isEqualTo("9");
        assertThat(calc("(128 + 64) * 3 - 72 / 4").output()).isEqualTo("558");
    }

    @Test
    @DisplayName("小数用 BigDecimal，不出现 0.30000000000000004")
    void decimalIsExact() {
        assertThat(calc("0.1 + 0.2").output()).isEqualTo("0.3");
    }

    @Test
    @DisplayName("除不尽时保留 10 位有效数字")
    void repeatingDivision() {
        assertThat(calc("1 / 3").output()).isEqualTo("0.3333333333");
    }

    @Test
    @DisplayName("一元负号")
    void unaryMinus() {
        assertThat(calc("-5 + 3").output()).isEqualTo("-2");
        assertThat(calc("-(2 + 3) * 2").output()).isEqualTo("-10");
    }

    @Test
    @DisplayName("整数结果不带多余的小数点")
    void trailingZerosStripped() {
        assertThat(calc("10 / 2").output()).isEqualTo("5");
        assertThat(calc("1.50 * 2").output()).isEqualTo("3");
    }

    @Test
    @DisplayName("除零返回错误结果，不抛异常")
    void divideByZero() {
        ToolResult result = calc("1 / 0");
        assertThat(result.ok()).isFalse();
        assertThat(result.output()).contains("除数为 0");
    }

    @Test
    @DisplayName("语法错误返回错误结果")
    void invalidExpression() {
        assertThat(calc("1 +").ok()).isFalse();
        assertThat(calc("(1 + 2").ok()).isFalse();
        assertThat(calc("abc").ok()).isFalse();
    }

    @Test
    @DisplayName("缺参数返回错误结果")
    void missingArgument() {
        ToolResult result = tool.execute(Map.of());
        assertThat(result.ok()).isFalse();
        assertThat(result.output()).contains("expression");
    }
}
