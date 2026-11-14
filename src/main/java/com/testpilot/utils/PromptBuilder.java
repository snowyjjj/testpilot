package com.testpilot.utils;

import com.testpilot.dto.MethodContext;
import com.testpilot.dto.MethodParameter;

import java.util.stream.Collectors;

/**
 * 构建 AI 单元测试生成的 Prompt。
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String buildTestPrompt(MethodContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下 Java 方法生成 JUnit 5 单元测试代码。\n\n");

        sb.append("【包名】\n").append(ctx.getPackageName().isBlank() ? "(默认包)" : ctx.getPackageName()).append("\n\n");

        sb.append("【被测类名】\n").append(ctx.getClassName()).append("\n\n");

        sb.append("【被测类完整源码】\n```java\n").append(ctx.getClassSource()).append("\n```\n\n");

        sb.append("【被测方法】\n");
        sb.append("- 方法名：").append(ctx.getMethodInfo().getName()).append("\n");
        sb.append("- 返回类型：").append(ctx.getMethodInfo().getReturnType()).append("\n");
        sb.append("- 访问修饰符：").append(ctx.getMethodInfo().getAccessModifier()).append("\n");
        sb.append("- static：").append(ctx.getMethodInfo().isStatic()).append("\n");
        sb.append("- 参数：");
        if (ctx.getMethodInfo().getParameters() == null || ctx.getMethodInfo().getParameters().isEmpty()) {
            sb.append("(无)");
        } else {
            sb.append(ctx.getMethodInfo().getParameters().stream()
                    .map(p -> p.getType() + " " + p.getName())
                    .collect(Collectors.joining(", ")));
        }
        sb.append("\n");
        sb.append("- 方法源码：\n```java\n").append(ctx.getMethodInfo().getSource()).append("\n```\n\n");

        sb.append("【要求】\n");
        sb.append("1. 使用 JUnit 5（org.junit.jupiter.api）编写测试。\n");
        sb.append("2. 测试类命名为 ").append(ctx.getClassName()).append("Test，放在与被测类相同的包下。\n");
        sb.append("3. 覆盖正常输入、边界条件、以及可能的异常情况。\n");
        sb.append("4. 测试方法使用清晰的英文命名，如 should_returnX_when_inputY。\n");
        sb.append("5. 不要修改被测类的原有业务逻辑。\n");
        sb.append("6. 不要编造不存在的字段、构造方法、依赖或业务行为；如果类有未展示的依赖且无法确定如何初始化，请仅测试能直接调用的简单场景。\n");
        sb.append("7. 只输出完整的 Java 源码，不要输出任何解释性文字。\n");

        return sb.toString();
    }

    /**
     * 从 LLM 返回内容中提取 Java 代码块。
     * 优先提取 ```java ... ```，其次 ``` ... ```，都没有则取全文。
     */
    public static String extractJavaCode(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        String trimmed = response.trim();

        // 匹配 ```java ... ```
        int javaStart = trimmed.indexOf("```java");
        if (javaStart >= 0) {
            int contentStart = javaStart + "```java".length();
            int end = trimmed.indexOf("```", contentStart);
            if (end > contentStart) {
                return trimmed.substring(contentStart, end).trim();
            }
        }

        // 匹配普通 ``` ... ```
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = trimmed.indexOf('\n', fenceStart);
            if (contentStart >= 0) {
                int end = trimmed.indexOf("```", contentStart + 1);
                if (end > contentStart) {
                    return trimmed.substring(contentStart + 1, end).trim();
                }
            }
        }

        // 没有代码块，取全文
        return trimmed;
    }

    /**
     * 基础校验：非空、包含 package 声明（如果原项目有包名）、包含 class 声明、至少一个 @Test。
     */
    public static String validateGeneratedCode(String code, String expectedPackage) {
        if (code == null || code.isBlank()) {
            return "生成的代码为空";
        }
        if (expectedPackage != null && !expectedPackage.isBlank()) {
            if (!code.contains("package " + expectedPackage + ";")) {
                return "生成的代码缺少正确的 package 声明: " + expectedPackage;
            }
        }
        if (!code.contains("class ")) {
            return "生成的代码缺少 class 声明";
        }
        if (!code.contains("@Test")) {
            return "生成的代码缺少 @Test 注解";
        }
        return null;
    }

    /**
     * 计算参数类型的简洁字符串表示（用于方法签名）。
     */
    public static String parameterSignature(MethodParameter p) {
        return p.getType();
    }
}
