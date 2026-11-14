package com.testpilot.utils;

import com.testpilot.dto.JUnitVersion;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.MethodParameter;

import java.util.stream.Collectors;

/**
 * 构建 AI 单元测试生成的 Prompt。
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /**
     * 根据检测到的 JUnit 版本构建测试生成 Prompt。
     */
    public static String buildTestPrompt(MethodContext ctx, JUnitVersion version) {
        StringBuilder sb = new StringBuilder();

        if (version == JUnitVersion.JUNIT4) {
            sb.append("请为以下 Java 方法生成 JUnit 4 单元测试代码。\n\n");
        } else if (version == JUnitVersion.JUNIT5) {
            sb.append("请为以下 Java 方法生成 JUnit 5 单元测试代码。\n\n");
        } else {
            sb.append("请为以下 Java 方法生成单元测试代码。\n\n");
        }

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
        if (version == JUnitVersion.JUNIT4) {
            sb.append("1. 使用 JUnit 4：测试注解为 org.junit.Test，断言使用 org.junit.Assert（assertEquals/assertTrue/assertNull 等）。\n");
            sb.append("2. 不要使用 @DisplayName、org.junit.jupiter.api.Assertions 或 assertThrows 等 JUnit 5 专属 API。\n");
            sb.append("3. 测试异常时使用 try-catch 捕获并 Assert.fail，不要使用 @Test(expected=...)。\n");
        } else if (version == JUnitVersion.JUNIT5) {
            sb.append("1. 使用 JUnit 5（org.junit.jupiter.api）：测试注解 org.junit.jupiter.api.Test，断言 org.junit.jupiter.api.Assertions。\n");
            sb.append("2. 可以使用 @DisplayName 注解和 assertThrows 断言异常。\n");
        } else {
            sb.append("1. 项目未检测到明确的 JUnit 版本，请使用 JUnit 4 语法（org.junit.Test + org.junit.Assert）以确保兼容性。\n");
        }
        sb.append("2. 测试类命名为 ").append(ctx.getClassName()).append("Test，放在与被测类相同的包下。\n");
        sb.append("3. 覆盖正常输入、边界条件、以及可能的异常情况。\n");
        sb.append("4. 测试方法使用清晰的英文命名，如 should_returnX_when_inputY。\n");
        sb.append("5. 不要修改被测类的原有业务逻辑。\n");
        sb.append("6. 不要编造不存在的字段、构造方法、依赖或业务行为；如果类有未展示的依赖且无法确定如何初始化，请仅测试能直接调用的简单场景。\n");
        sb.append("7. 不要生成不存在的 import，不要生成无用 import。\n");
        sb.append("8. 断言必须与被测方法的实际行为一致，不要编造不存在的异常、返回值或业务逻辑。\n");
        sb.append("9. 只输出完整的 Java 源码，不要输出任何解释性文字。\n");

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
     * 基础校验：非空、包含 package 声明、包含 class 声明、至少一个 @Test，
     * 并根据 JUnit 版本检查对应的 import。
     */
    public static String validateGeneratedCode(String code, String expectedPackage, JUnitVersion version) {
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
        if (version == JUnitVersion.JUNIT4) {
            if (!code.contains("import org.junit.Test")) {
                return "JUnit 4 测试缺少 org.junit.Test import";
            }
            if (code.contains("org.junit.jupiter")) {
                return "JUnit 4 测试不应包含 JUnit 5 (org.junit.jupiter) import";
            }
        } else if (version == JUnitVersion.JUNIT5) {
            if (!code.contains("import org.junit.jupiter.api.Test")) {
                return "JUnit 5 测试缺少 org.junit.jupiter.api.Test import";
            }
        }
        return null;
    }

    /**
     * 兼容旧调用（不做版本相关 import 校验）。
     */
    public static String validateGeneratedCode(String code, String expectedPackage) {
        return validateGeneratedCode(code, expectedPackage, JUnitVersion.UNKNOWN);
    }

    /**
     * 计算参数类型的简洁字符串表示（用于方法签名）。
     */
    public static String parameterSignature(MethodParameter p) {
        return p.getType();
    }
}
