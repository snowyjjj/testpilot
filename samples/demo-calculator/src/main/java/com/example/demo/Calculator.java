package com.example.demo;

/**
 * 演示用计算器。
 * <p>
 * 方法刻意保持简单，便于演示 TestPilot 的「上传 → 生成测试 → 执行 → 覆盖率」链路：
 * <ul>
 *   <li>{@link #add(int, int)}、{@link #subtract(int, int)}、{@link #multiply(int, int)}
 *       为纯函数，生成的测试通常一次通过。</li>
 *   <li>{@link #divide(int, int)} 在除数为 0 时抛出异常，用来演示分支覆盖与异常用例。</li>
 * </ul>
 */
public class Calculator {

    public int add(int a, int b) {
        return a + b;
    }

    public int subtract(int a, int b) {
        return a - b;
    }

    public int multiply(int a, int b) {
        return a * b;
    }

    /**
     * 整数除法。
     *
     * @param dividend 被除数
     * @param divisor  除数，不能为 0
     * @return 商
     * @throws ArithmeticException 除数为 0 时抛出
     */
    public int divide(int dividend, int divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("除数不能为 0");
        }
        return dividend / divisor;
    }
}
