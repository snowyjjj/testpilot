package com.testpilot.utils;

import com.testpilot.dto.TestClassStat;
import com.testpilot.dto.TestQualityStaticStats;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 测试代码静态质量分析器。
 * <p>
 * 遍历 Maven 项目根目录下 {@code src/test/java} 中的所有 Java 测试文件，
 * 基于 JavaParser AST 统计测试类数量、测试方法数量、断言数量、空测试方法、
 * 无断言测试方法、跳过测试方法，以及解析失败文件。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>兼容 JUnit4（{@code org.junit.Test}）与 JUnit5（{@code org.junit.jupiter.api.Test}），
 *       并通过 import 解析明确排除 TestNG {@code org.testng.annotations.Test}。</li>
 *   <li>逐方法分析以区分「空测试」与「无断言测试」，不修改 {@link PromptBuilder} 现有行为。</li>
 *   <li>文件级断言总数复用 {@link PromptBuilder#countAssertions(String)}。</li>
 *   <li>路径安全：遍历前校验 testRoot 整条路径链，逐文件读取前再次校验，
 *       与 {@link TestFileManager#assertPathWithinProject} 保持一致。</li>
 *   <li>单个文件解析失败只记录该文件，不中断整个项目的分析。</li>
 * </ul>
 */
public final class TestQualityAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TestQualityAnalyzer.class);

    private TestQualityAnalyzer() {
    }

    /**
     * 分析指定 Maven 项目根目录下的测试代码静态质量。
     *
     * @param mavenProjectDir Maven 项目根目录（包含 src/test/java 的目录）
     * @return 聚合后的静态统计结果；若 {@code src/test/java} 不存在或路径不安全则返回全零统计
     */
    public static TestQualityStaticStats analyze(Path mavenProjectDir) {
        if (mavenProjectDir == null) {
            throw new IllegalArgumentException("mavenProjectDir 不能为空");
        }

        TestQualityStaticStats result = new TestQualityStaticStats();

        Path testRoot = mavenProjectDir.resolve("src").resolve("test").resolve("java");
        // NOFOLLOW_LINKS：若 testRoot 最后一级自身是符号链接，则不视为有效目录
        if (!Files.isDirectory(testRoot, LinkOption.NOFOLLOW_LINKS)) {
            log.debug("测试源码目录不存在，跳过静态分析: {}", testRoot);
            return result;
        }

        // 完整路径链校验：复用 TestFileManager.assertPathWithinProject，
        // 覆盖 mavenProjectDir 非符号链接、normalize 前缀、每级父目录非符号链接、toRealPath 二次确认。
        // 仅检查最后一级不足以防御 src 或 src/test 被替换为指向项目外的符号链接。
        try {
            TestFileManager.assertPathWithinProject(mavenProjectDir, testRoot);
        } catch (IOException e) {
            log.warn("测试源码目录路径不安全（含符号链接或越界），跳过分析: {}", testRoot, e);
            return result;
        }

        try (Stream<Path> stream = Files.walk(testRoot)) {
            List<Path> javaFiles = stream
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();

            for (Path javaFile : javaFiles) {
                analyzeFile(mavenProjectDir, javaFile, result);
            }
        } catch (IOException e) {
            // 遍历失败时记录日志，已统计的结果保留
            log.warn("遍历测试源码目录失败: {}", testRoot, e);
        }

        // 解析失败文件数与列表保持一致
        result.setParseFailedFileCount(result.getParseFailedFiles().size());
        return result;
    }

    private static void analyzeFile(Path mavenProjectDir, Path javaFile, TestQualityStaticStats result) {
        String relativePath = toRelativePath(mavenProjectDir, javaFile);

        // 逐文件路径链校验：避免 walk 过滤后文件被替换为指向项目外的符号链接（TOCTOU）
        try {
            TestFileManager.assertPathWithinProject(mavenProjectDir, javaFile);
        } catch (IOException e) {
            log.warn("测试文件路径不安全，跳过: {}", javaFile, e);
            result.getParseFailedFiles().add(relativePath);
            return;
        }

        String code;
        try {
            code = Files.readString(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取测试文件失败: {}", javaFile, e);
            result.getParseFailedFiles().add(relativePath);
            return;
        }

        CompilationUnit cu;
        try {
            // JavaParser 默认容错解析：语法错误时仍可能返回残缺的 CompilationUnit，
            // 因此必须用 ParseResult.isSuccessful() 判断，而非仅依赖是否抛异常。
            ParseResult<CompilationUnit> parseResult = new JavaParser().parse(code);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                log.debug("JavaParser 解析测试文件失败（存在语法错误）: {}", javaFile);
                result.getParseFailedFiles().add(relativePath);
                return;
            }
            cu = parseResult.getResult().get();
        } catch (Exception e) {
            // 解析失败只记录该文件，不中断整体分析
            log.debug("JavaParser 解析测试文件失败: {}", javaFile, e);
            result.getParseFailedFiles().add(relativePath);
            return;
        }

        // 收集完整 import 上下文：JUnit/TestNG 的 Test、Ignore、Disabled 与断言静态导入
        ImportContext ctx = collectImportContext(cu);

        int fileTestMethods = 0;
        int fileEmpty = 0;
        int fileNoAssertion = 0;
        int fileSkipped = 0;
        String testClassName = null;

        for (TypeDeclaration<?> type : cu.getTypes()) {
            boolean typeHasTest = false;
            for (MethodDeclaration method : type.findAll(MethodDeclaration.class)) {
                if (!isTestMethod(method, ctx)) {
                    continue;
                }
                typeHasTest = true;
                fileTestMethods++;

                if (isSkippedTestMethod(method, ctx)) {
                    fileSkipped++;
                }
                if (isEmptyTestMethod(method)) {
                    fileEmpty++;
                }
                if (countAssertionsInMethod(method, ctx) == 0) {
                    fileNoAssertion++;
                }
            }
            if (typeHasTest && testClassName == null) {
                testClassName = type.getNameAsString();
            }
        }

        // 文件级断言总数复用 PromptBuilder.countAssertions（覆盖全部方法，含 setUp 等）
        int fileAssertions = PromptBuilder.countAssertions(code);

        if (fileTestMethods > 0) {
            result.setTestClassCount(result.getTestClassCount() + 1);
            result.setTotalTestMethods(result.getTotalTestMethods() + fileTestMethods);
            result.setEmptyTestMethods(result.getEmptyTestMethods() + fileEmpty);
            result.setNoAssertionTestMethods(result.getNoAssertionTestMethods() + fileNoAssertion);
            result.setSkippedTestMethods(result.getSkippedTestMethods() + fileSkipped);
            result.setTotalAssertions(result.getTotalAssertions() + fileAssertions);

            TestClassStat stat = new TestClassStat();
            stat.setFilePath(relativePath);
            stat.setClassName(testClassName);
            stat.setTestMethodCount(fileTestMethods);
            stat.setAssertionCount(fileAssertions);
            stat.setEmptyTestMethodCount(fileEmpty);
            stat.setNoAssertionTestMethodCount(fileNoAssertion);
            stat.setSkippedTestMethodCount(fileSkipped);
            result.getClassStats().add(stat);
        }
    }

    /**
     * 判断方法是否为 JUnit4 / JUnit5 测试方法。
     * <p>
     * 全限定名注解（{@code @org.junit.Test}、{@code @org.junit.jupiter.api.Test}）直接识别；
     * 简单名 {@code @Test} 只有在明确导入 JUnit 注解且未导入 TestNG {@code @Test} 时才识别，
     * 避免把 TestNG 或用户自定义的 {@code @Test} 误判为 JUnit 测试。
     */
    private static boolean isTestMethod(MethodDeclaration method, ImportContext ctx) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("org.junit.Test".equals(name) || "org.junit.jupiter.api.Test".equals(name)) {
                return true;
            }
            if ("Test".equals(name)) {
                // 简单名 @Test：先排除 TestNG 明确导入的冲突情况，再检查 JUnit 导入
                if (ctx.testngTestImport || ctx.testngTestStar) {
                    continue; // TestNG @Test 不视为 JUnit
                }
                if (ctx.junit4TestImport || ctx.junit5TestImport
                        || ctx.junit4TestStar || ctx.junit5TestStar) {
                    return true;
                }
                // 无明确 JUnit import：可能是用户自定义 @interface Test，或默认包，不计数
            }
        }
        return false;
    }

    /**
     * 判断测试方法是否被 @Ignore（JUnit4）或 @Disabled（JUnit5）标注。
     * <p>
     * 与 {@link #isTestMethod} 相同的 import 解析策略：全限定名直接识别；
     * 简单名只有在明确导入对应的 JUnit 注解时才识别，避免自定义同名注解被误判。
     */
    private static boolean isSkippedTestMethod(MethodDeclaration method, ImportContext ctx) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("org.junit.Ignore".equals(name) || "org.junit.jupiter.api.Disabled".equals(name)) {
                return true;
            }
            if ("Ignore".equals(name)) {
                if (ctx.junit4IgnoreImport || ctx.junit4IgnoreStar) {
                    return true;
                }
            }
            if ("Disabled".equals(name)) {
                if (ctx.junit5DisabledImport || ctx.junit5DisabledStar) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断测试方法是否为空：方法体不存在或不含任何实际语句。
     * 注释、空行不计入语句，因此纯注释的测试方法也视为空。
     */
    private static boolean isEmptyTestMethod(MethodDeclaration method) {
        Optional<BlockStmt> body = method.getBody();
        if (body.isEmpty()) {
            return true;
        }
        return body.get().getStatements().isEmpty();
    }

    /**
     * 收集当前编译单元的完整 import 上下文，用于注解归属判定与断言静态导入识别。
     */
    private static ImportContext collectImportContext(CompilationUnit cu) {
        ImportContext ctx = new ImportContext();
        for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
            String name = imp.getNameAsString();
            boolean asterisk = imp.isAsterisk();
            boolean isStatic = imp.isStatic();

            if (!isStatic) {
                // ===== 非静态 import：Test / Ignore / Disabled 注解归属 =====
                // JUnit4 @Test: import org.junit.Test 或 import org.junit.*
                if ("org.junit.Test".equals(name)) {
                    ctx.junit4TestImport = true;
                } else if ("org.junit.Ignore".equals(name)) {
                    ctx.junit4IgnoreImport = true;
                } else if ("org.junit.jupiter.api.Test".equals(name)) {
                    ctx.junit5TestImport = true;
                } else if ("org.junit.jupiter.api.Disabled".equals(name)) {
                    ctx.junit5DisabledImport = true;
                } else if (asterisk && "org.junit".equals(name)) {
                    ctx.junit4TestStar = true;
                    ctx.junit4IgnoreStar = true;
                } else if (asterisk && "org.junit.jupiter.api".equals(name)) {
                    ctx.junit5TestStar = true;
                    ctx.junit5DisabledStar = true;
                } else if ("org.testng.annotations.Test".equals(name)) {
                    ctx.testngTestImport = true;
                } else if (asterisk && "org.testng.annotations".equals(name)) {
                    ctx.testngTestStar = true;
                }
            } else {
                // ===== 静态 import：断言方法识别（与 PromptBuilder 规则一致）=====
                if (name.equals("org.junit.Assert") || name.startsWith("org.junit.Assert.")) {
                    if (asterisk) {
                        ctx.junit4AssertStar = true;
                    } else {
                        ctx.staticAssertImports.add(imp.getName().getIdentifier());
                    }
                } else if (name.equals("org.junit.jupiter.api.Assertions")
                        || name.startsWith("org.junit.jupiter.api.Assertions.")) {
                    if (asterisk) {
                        ctx.junit5AssertionsStar = true;
                    } else {
                        ctx.staticAssertImports.add(imp.getName().getIdentifier());
                    }
                }
            }
        }
        return ctx;
    }

    /**
     * 统计单个测试方法体内的 JUnit 断言调用数量。
     * 识别规则与 {@link PromptBuilder#countAssertions(String)} 一致：
     * 仅 JUnit4 {@code Assert.assert*} 与 JUnit5 {@code Assertions.assert*}，
     * 不会把注释、字符串或自定义 assertXxx 方法误认为断言。
     */
    private static int countAssertionsInMethod(MethodDeclaration method, ImportContext ctx) {
        Optional<BlockStmt> body = method.getBody();
        if (body.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (MethodCallExpr call : body.get().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!name.startsWith("assert")) {
                continue;
            }
            Optional<Expression> scope = call.getScope();
            if (scope.isPresent()) {
                String scopeStr = scope.get().toString();
                if ("Assert".equals(scopeStr) || "org.junit.Assert".equals(scopeStr)
                        || "Assertions".equals(scopeStr)
                        || "org.junit.jupiter.api.Assertions".equals(scopeStr)) {
                    count++;
                }
            } else {
                if (ctx.junit4AssertStar || ctx.junit5AssertionsStar
                        || ctx.staticAssertImports.contains(name)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String toRelativePath(Path base, Path target) {
        try {
            return base.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (Exception e) {
            return target.getFileName().toString();
        }
    }

    /**
     * 完整 import 上下文载体：JUnit/TestNG Test/Ignore/Disabled 归属 + 断言静态导入。
     */
    private static final class ImportContext {
        // ===== JUnit4 / JUnit5 @Test =====
        boolean junit4TestImport;    // import org.junit.Test
        boolean junit5TestImport;    // import org.junit.jupiter.api.Test
        boolean junit4TestStar;      // import org.junit.*
        boolean junit5TestStar;      // import org.junit.jupiter.api.*

        // ===== TestNG @Test（显式排除）=====
        boolean testngTestImport;    // import org.testng.annotations.Test
        boolean testngTestStar;      // import org.testng.annotations.*

        // ===== @Ignore / @Disabled =====
        boolean junit4IgnoreImport;
        boolean junit5DisabledImport;
        boolean junit4IgnoreStar;    // import org.junit.* 同时覆盖 Ignore
        boolean junit5DisabledStar;  // import org.junit.jupiter.api.* 同时覆盖 Disabled

        // ===== 断言静态导入（复用原 AssertImportContext 字段）=====
        final Set<String> staticAssertImports = new HashSet<>();
        boolean junit4AssertStar;
        boolean junit5AssertionsStar;
    }
}
