package com.testpilot.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.testpilot.dto.FailureFeedback;
import com.testpilot.dto.FailureFeedback.FailedCaseInfo;
import com.testpilot.dto.JUnitVersion;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.MethodParameter;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 构建 AI 单元测试生成的 Prompt。
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /**
     * 根据被测源文件的相对路径推导测试文件路径。
     * 例如：src/main/java/com/example/Calculator.java → src/test/java/com/example/CalculatorTest.java
     * 兼容 ZIP 内存在单层子目录的情况。
     * 返回相对于 projectDir 解析并 normalize 后的路径，并做边界校验。
     */
    public static Path resolveTestFilePath(Path projectDir, MethodContext ctx) {
        String sourceRelative = ctx.getSourceFilePath();
        String normalized = sourceRelative.replace("\\", "/");

        int markerIdx = normalized.indexOf("src/main/java");
        String testRelative;
        if (markerIdx >= 0) {
            testRelative = normalized.substring(0, markerIdx)
                    + "src/test/java"
                    + normalized.substring(markerIdx + "src/main/java".length());
        } else {
            int lastSlash = normalized.lastIndexOf('/');
            String dir = lastSlash >= 0 ? normalized.substring(0, lastSlash) : "";
            String file = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
            testRelative = (dir.isEmpty() ? "" : dir + "/") + file;
        }

        String className = ctx.getClassName();
        testRelative = testRelative.replace(className + ".java", className + "Test.java");

        Path resolved = projectDir.resolve(testRelative).normalize();
        if (!resolved.startsWith(projectDir)) {
            throw new IllegalStateException("测试文件路径越界: " + testRelative);
        }
        return resolved;
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
     * 增强修复代码校验：
     * 1. 测试类名必须为 {expectedClassName}Test。
     * 2. 修复后 @Test 方法数量不得少于修复前（防止删除测试场景）。
     * 3. 修复后断言数量不得少于修复前（防止弱化断言）。
     * 4. 不允许使用 @Disabled / @Ignore 等跳过注解。
     * 5. 必须包含断言方法（assert*）。
     * 6. 基础校验（package/class/@Test/import）仍需通过。
     *
     * @return null 表示校验通过，否则返回错误描述
     */
    public static String validateRepairedCode(String originalCode, String repairedCode,
                                               String expectedClassName, String expectedPackage,
                                               JUnitVersion version) {
        // 基础校验
        String baseError = validateGeneratedCode(repairedCode, expectedPackage, version);
        if (baseError != null) {
            return baseError;
        }

        // 1. 测试类名校验
        String expectedTestClass = expectedClassName + "Test";
        // 匹配 class XxxTest 声明（允许 public / 普通 / final / abstract 等修饰符）
        java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile(
                "\\bclass\\s+" + java.util.regex.Pattern.quote(expectedTestClass) + "\\b");
        if (!classPattern.matcher(repairedCode).find()) {
            return "修复后的测试类名必须为 " + expectedTestClass;
        }

        // 2. @Test 方法数量对比（使用 JavaParser 精确统计）
        int originalTestCount = countTestMethods(originalCode);
        int repairedTestCount = countTestMethods(repairedCode);
        if (repairedTestCount < originalTestCount) {
            return "修复后测试方法数量(" + repairedTestCount + ")少于修复前(" + originalTestCount + ")，不允许删除测试场景";
        }
        if (repairedTestCount == 0) {
            return "修复后的代码没有任何 @Test 方法";
        }

        // 3. 断言数量对比（防止通过删除断言弱化测试）
        int originalAssertCount = countAssertions(originalCode);
        int repairedAssertCount = countAssertions(repairedCode);
        if (repairedAssertCount < originalAssertCount) {
            return "修复后断言数量(" + repairedAssertCount + ")少于修复前(" + originalAssertCount + ")，不允许弱化断言";
        }
        if (repairedAssertCount == 0) {
            return "修复后的代码未包含任何断言方法（assert*），测试无效";
        }

        // 4. 跳过注解校验
        if (containsSkipAnnotation(repairedCode)) {
            return "修复后的代码包含 @Disabled / @Ignore 等跳过注解，不允许跳过失败测试";
        }

        return null;
    }

    /**
     * 统计代码中 @Test 注解的方法数量。
     * 优先使用 JavaParser 精确统计；解析失败时回退到正则粗略统计。
     */
    public static int countTestMethods(String code) {
        if (code == null || code.isBlank()) {
            return 0;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            int count = 0;
            for (MethodDeclaration m : methods) {
                if (m.getAnnotationByName("Test").isPresent()) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            // JavaParser 解析失败，回退到正则
            int count = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("@Test\\b").matcher(code);
            while (m.find()) {
                count++;
            }
            return count;
        }
    }

    /**
     * 统计代码中断言方法调用的数量（assert* 方法调用）。
     * 优先使用 JavaParser 精确统计；解析失败时回退到正则粗略统计。
     */
    public static int countAssertions(String code) {
        if (code == null || code.isBlank()) {
            return 0;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            List<MethodCallExpr> calls = cu.findAll(MethodCallExpr.class);
            int count = 0;
            for (MethodCallExpr call : calls) {
                String name = call.getNameAsString();
                if (name.startsWith("assert")) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            // JavaParser 解析失败，回退到正则
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\bAssert(ions)?\\.assert\\w+|\\bassert\\w+\\s*\\(");
            java.util.regex.Matcher m = p.matcher(code);
            int count = 0;
            while (m.find()) {
                count++;
            }
            return count;
        }
    }

    /**
     * 检测代码中是否包含跳过测试的注解（@Disabled、@Ignore）。
     */
    public static boolean containsSkipAnnotation(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return code.contains("@Disabled") || code.contains("@Ignore");
    }

    /**
     * 检测代码中是否包含断言调用。
     * JUnit 4: org.junit.Assert.assert* 或 Assert.assert*
     * JUnit 5: org.junit.jupiter.api.Assertions.assert* 或 Assertions.assert*
     */
    public static boolean containsAssertion(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\bAssert(ions)?\\.assert|org\\.junit\\.jupiter\\.api\\.Assertions\\.assert|"
                        + "org\\.junit\\.Assert\\.assert");
        return p.matcher(code).find();
    }

    /**
     * 计算参数类型的简洁字符串表示（用于方法签名）。
     */
    public static String parameterSignature(MethodParameter p) {
        return p.getType();
    }

    /**
     * 构建测试修复 Prompt。将当前测试代码、被测源码、JUnit 版本、失败反馈和修复轮次提供给 LLM。
     */
    public static String buildRepairPrompt(MethodContext ctx, JUnitVersion version,
                                           String currentTestCode, FailureFeedback feedback,
                                           int retryNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名 Java 单元测试修复专家。以下测试代码编译失败或执行失败，请根据反馈信息修复。\n\n");

        sb.append("【当前修复轮次】\n第 ").append(retryNumber).append(" 轮修复\n\n");

        sb.append("【包名】\n").append(ctx.getPackageName().isBlank() ? "(默认包)" : ctx.getPackageName()).append("\n\n");
        sb.append("【被测类名】\n").append(ctx.getClassName()).append("\n\n");
        sb.append("【被测类完整源码】\n```java\n").append(ctx.getClassSource()).append("\n```\n\n");
        sb.append("【被测方法源码】\n```java\n").append(ctx.getMethodInfo().getSource()).append("\n```\n\n");

        sb.append("【当前 JUnit 版本】\n");
        if (version == JUnitVersion.JUNIT4) {
            sb.append("JUnit 4（org.junit.Test + org.junit.Assert）\n\n");
        } else if (version == JUnitVersion.JUNIT5) {
            sb.append("JUnit 5（org.junit.jupiter.api.Test + org.junit.jupiter.api.Assertions）\n\n");
        } else {
            sb.append("未知（默认使用 JUnit 4 语法）\n\n");
        }

        sb.append("【当前测试代码】\n```java\n").append(currentTestCode).append("\n```\n\n");

        sb.append("【失败反馈】\n");
        if (feedback != null) {
            sb.append("类型：").append(feedback.getFailureType() == FailureFeedback.FailureType.COMPILE
                    ? "编译错误" : "测试执行失败").append("\n");
            sb.append("摘要：").append(feedback.getSummary()).append("\n\n");
            if (feedback.getFailureType() == FailureFeedback.FailureType.COMPILE) {
                sb.append("编译错误详情：\n");
                for (String err : feedback.getCompileErrors()) {
                    sb.append("- ").append(err).append("\n");
                }
            } else {
                sb.append("失败用例详情：\n");
                for (FailedCaseInfo fc : feedback.getFailedCases()) {
                    sb.append("- 类：").append(fc.getClassName())
                            .append("，方法：").append(fc.getMethodName()).append("\n");
                    sb.append("  异常类型：").append(fc.getExceptionType()).append("\n");
                    if (fc.getMessage() != null && !fc.getMessage().isBlank()) {
                        sb.append("  消息：").append(fc.getMessage()).append("\n");
                    }
                    if (fc.getStackTrace() != null && !fc.getStackTrace().isBlank()) {
                        sb.append("  堆栈：\n").append(fc.getStackTrace()).append("\n");
                    }
                }
            }
        } else {
            sb.append("(无详细反馈)\n");
        }
        sb.append("\n");

        sb.append("【修复要求】\n");
        sb.append("1. 只能修改测试代码，绝对不允许修改被测业务源码。\n");
        sb.append("2. 不允许为了通过测试而删除原有测试场景或减少测试方法。\n");
        sb.append("3. 不允许随意删除或弱化断言；修复必须针对实际的编译错误或测试失败原因。\n");
        sb.append("4. 不允许使用 @Disabled、@Ignore 或其他方式跳过失败的测试。\n");
        sb.append("5. 不允许编造不存在的类、方法、字段或依赖。\n");
        sb.append("6. 保留已有的有效测试场景，只修复有问题的部分。\n");
        sb.append("7. 不生成不存在的 import，不生成无用 import。\n");
        sb.append("8. 断言必须与被测方法的实际行为一致。\n");
        if (version == JUnitVersion.JUNIT4) {
            sb.append("9. 继续使用 JUnit 4：org.junit.Test + org.junit.Assert，不使用 JUnit 5 专属 API。\n");
        } else if (version == JUnitVersion.JUNIT5) {
            sb.append("9. 继续使用 JUnit 5：org.junit.jupiter.api.Test + org.junit.jupiter.api.Assertions。\n");
        }
        sb.append("10. 测试类命名为 ").append(ctx.getClassName()).append("Test，放在与被测类相同的包下。\n");
        sb.append("11. 只输出完整的 Java 源码，不要输出任何解释性文字。\n");

        return sb.toString();
    }
}
