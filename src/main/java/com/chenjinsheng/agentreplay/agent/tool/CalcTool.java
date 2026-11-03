package com.chenjinsheng.agentreplay.agent.tool;

import com.chenjinsheng.agentreplay.agent.model.ToolResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 四则运算工具。
 *
 * <p>为什么自己写解析器、不直接用现成的表达式引擎？
 * 一是 JDK 17 已经移除了 Nashorn，{@code ScriptEngine} 这条路不通；
 * 二是引一个第三方引擎进来，工具就多了一层「它会不会自己访问网络/文件」的不确定性——
 * 而本项目的主题恰恰是「把不确定性关在笼子里」。</p>
 *
 * <p>用 {@link BigDecimal} 而不是 {@code double}：{@code 0.1 + 0.2} 用 double 算出来是
 * {@code 0.30000000000000004}，模型看到这个数字会以为自己算错了，然后反复重试。</p>
 *
 * <p>只支持 {@code + - * /} 和括号，按常规优先级解析。幂运算、函数调用一概不支持——
 * 用不到，而且每多一种语法就多一批边界要测。除不尽时保留 10 位有效数字。</p>
 */
@Component
public class CalcTool implements Tool {

    private static final MathContext MC = new MathContext(10);

    @Override
    public String name() {
        return "calc";
    }

    @Override
    public String description() {
        return "计算一个四则运算表达式，支持 + - * / 和括号，例如 (1+2)*3/4。结果是精确的十进制值。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("expression", Map.of("type", "string", "description", "要计算的表达式"));
        schema.put("properties", props);
        schema.put("required", java.util.List.of("expression"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object raw = arguments.get("expression");
        if (raw == null || raw.toString().isBlank()) {
            return ToolResult.error("缺少参数 expression");
        }
        try {
            BigDecimal value = new Parser(raw.toString()).parse();
            return ToolResult.ok(value.stripTrailingZeros().toPlainString());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("表达式无效：" + e.getMessage());
        }
    }

    /**
     * 递归下降解析器。
     *
     * <pre>
     *   expr   := term (('+' | '-') term)*
     *   term   := factor (('*' | '/') factor)*
     *   factor := number | '(' expr ')' | '-' factor
     * </pre>
     *
     * <p>写递归下降而不是「先转后缀再求值」，是因为这份文法就这么三层，
     * 递归下降照着文法抄下来就是实现，栈也是编译器帮你管的，没有额外的手工栈要维护。</p>
     */
    private static final class Parser {

        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        BigDecimal parse() {
            BigDecimal value = expr();
            skipSpaces();
            if (pos < src.length()) {
                throw new IllegalArgumentException("位置 " + pos + " 有无法识别的内容：" + src.substring(pos));
            }
            return value;
        }

        private BigDecimal expr() {
            BigDecimal value = term();
            while (true) {
                skipSpaces();
                if (peek('+')) {
                    pos++;
                    value = value.add(term(), MC);
                } else if (peek('-')) {
                    pos++;
                    value = value.subtract(term(), MC);
                } else {
                    return value;
                }
            }
        }

        private BigDecimal term() {
            BigDecimal value = factor();
            while (true) {
                skipSpaces();
                if (peek('*')) {
                    pos++;
                    value = value.multiply(factor(), MC);
                } else if (peek('/')) {
                    pos++;
                    BigDecimal divisor = factor();
                    if (divisor.signum() == 0) {
                        throw new IllegalArgumentException("除数为 0");
                    }
                    value = value.divide(divisor, MC);
                } else {
                    return value;
                }
            }
        }

        private BigDecimal factor() {
            skipSpaces();
            if (peek('-')) {
                pos++;
                return factor().negate();
            }
            if (peek('+')) {
                pos++;
                return factor();
            }
            if (peek('(')) {
                pos++;
                BigDecimal inner = expr();
                skipSpaces();
                if (!peek(')')) {
                    throw new IllegalArgumentException("缺少右括号");
                }
                pos++;
                return inner;
            }
            return number();
        }

        private BigDecimal number() {
            skipSpaces();
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("位置 " + pos + " 应该是数字");
            }
            String text = src.substring(start, pos);
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("不是合法数字：" + text);
            }
        }

        private boolean peek(char c) {
            return pos < src.length() && src.charAt(pos) == c;
        }

        private void skipSpaces() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
