package com.example.agentdemo.tool;

import com.example.agentdemo.common.BusinessException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

@Service
public class ToolService {

    private static final Pattern SAFE_EXPRESSION = Pattern.compile("[0-9+\\-*/().\\s]+");
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    @Tool(name = "getCurrentTime", description = "Return current server time in ISO-8601 format.")
    public String getCurrentTime() {
        return OffsetDateTime.now(ZoneId.systemDefault()).toString();
    }

    @Tool(name = "calculate", description = "Calculate a safe arithmetic expression with +, -, *, / and parentheses.")
    public String calculate(@ToolParam(description = "Arithmetic expression, for example: (1 + 2) * 3") String expression) {
        if (expression == null || expression.isBlank()) {
            throw new BusinessException("INVALID_EXPRESSION", "Expression is required");
        }
        if (expression.length() > 128 || !SAFE_EXPRESSION.matcher(expression).matches()) {
            throw new BusinessException("INVALID_EXPRESSION", "Only digits, spaces, +, -, *, / and parentheses are allowed");
        }
        BigDecimal result = new Parser(expression).parse();
        return result.stripTrailingZeros().toPlainString();
    }

    public ToolExecutionLog executeGetCurrentTime() {
        Instant startedAt = Instant.now();
        ToolExecutionLog.EmptyInput input = new ToolExecutionLog.EmptyInput();
        try {
            String output = getCurrentTime();
            return new ToolExecutionLog("getCurrentTime", input, output, true, null, startedAt, Instant.now());
        }
        catch (RuntimeException ex) {
            return new ToolExecutionLog("getCurrentTime", input, null, false, ex.getMessage(), startedAt,
                    Instant.now());
        }
    }

    public ToolExecutionLog executeCalculate(String expression) {
        Instant startedAt = Instant.now();
        ToolExecutionLog.CalculateInput input = new ToolExecutionLog.CalculateInput(expression == null ? "" : expression);
        try {
            String output = calculate(expression);
            return new ToolExecutionLog("calculate", input, output, true, null, startedAt, Instant.now());
        }
        catch (RuntimeException ex) {
            return new ToolExecutionLog("calculate", input, null, false, ex.getMessage(), startedAt, Instant.now());
        }
    }

    private static final class Parser {

        private final String expression;
        private int position;

        private Parser(String expression) {
            this.expression = expression;
        }

        private BigDecimal parse() {
            BigDecimal value = parseExpression();
            skipWhitespace();
            if (position != expression.length()) {
                throw new BusinessException("INVALID_EXPRESSION", "Unexpected token at position " + position);
            }
            return value;
        }

        private BigDecimal parseExpression() {
            BigDecimal value = parseTerm();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    value = value.add(parseTerm(), MATH_CONTEXT);
                }
                else if (match('-')) {
                    value = value.subtract(parseTerm(), MATH_CONTEXT);
                }
                else {
                    return value;
                }
            }
        }

        private BigDecimal parseTerm() {
            BigDecimal value = parseFactor();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    value = value.multiply(parseFactor(), MATH_CONTEXT);
                }
                else if (match('/')) {
                    BigDecimal divisor = parseFactor();
                    if (BigDecimal.ZERO.compareTo(divisor) == 0) {
                        throw new BusinessException("INVALID_EXPRESSION", "Division by zero is not allowed");
                    }
                    value = value.divide(divisor, MATH_CONTEXT);
                }
                else {
                    return value;
                }
            }
        }

        private BigDecimal parseFactor() {
            skipWhitespace();
            if (match('+')) {
                return parseFactor();
            }
            if (match('-')) {
                return parseFactor().negate(MATH_CONTEXT);
            }
            if (match('(')) {
                BigDecimal value = parseExpression();
                skipWhitespace();
                if (!match(')')) {
                    throw new BusinessException("INVALID_EXPRESSION", "Missing closing parenthesis");
                }
                return value;
            }
            return parseNumber();
        }

        private BigDecimal parseNumber() {
            skipWhitespace();
            int start = position;
            while (position < expression.length()) {
                char ch = expression.charAt(position);
                if (Character.isDigit(ch) || ch == '.') {
                    position++;
                }
                else {
                    break;
                }
            }
            if (start == position) {
                throw new BusinessException("INVALID_EXPRESSION", "Number expected at position " + position);
            }
            try {
                return new BigDecimal(expression.substring(start, position), MATH_CONTEXT);
            }
            catch (NumberFormatException ex) {
                throw new BusinessException("INVALID_EXPRESSION", "Invalid number at position " + start);
            }
        }

        private boolean match(char expected) {
            if (position < expression.length() && expression.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < expression.length() && Character.isWhitespace(expression.charAt(position))) {
                position++;
            }
        }

    }

}
