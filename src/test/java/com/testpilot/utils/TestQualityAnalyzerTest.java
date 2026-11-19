package com.testpilot.utils;

import com.testpilot.dto.TestQualityStaticStats;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class TestQualityAnalyzerTest {

    @TempDir
    Path tempDir;

    private Path writeTestFile(Path projectDir, String relativePath, String content) throws IOException {
        Path file = projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ============ 基础 JUnit5 ============

    private static final String JUNIT5_CALCULATOR = """
            package com.example;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            public class CalculatorTest {
                @Test
                void testAdd() {
                    assertEquals(2, 1 + 1);
                    assertTrue(true);
                }
                @Test
                void testEmpty() {
                    // 仅含注释，没有实际语句
                }
                @Test
                void testNoAssertion() {
                    int x = 1 + 1;
                    System.out.println(x);
                }
                @Test
                @org.junit.jupiter.api.Disabled
                void testSkipped() {
                    assertEquals(1, 1);
                }
            }
            """;

    private static final String JUNIT4_USER = """
            package com.example;
            import org.junit.Test;
            import org.junit.Assert;

            public class UserServiceTest {
                @Test
                public void testUser() {
                    Assert.assertEquals(1, 1);
                }
                @Test
                @org.junit.Ignore
                public void testOld() {
                    Assert.assertTrue(true);
                }
            }
            """;

    // ============ JUnit5 基础统计 ============

    @Test
    void analyze_junit5_countsCorrectly() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("proj"));
        writeTestFile(project, "src/test/java/com/example/CalculatorTest.java", JUNIT5_CALCULATOR);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(1, stats.getTestClassCount());
        assertEquals(4, stats.getTotalTestMethods(), "4 个 @Test 方法（含被 @Disabled 的）");
        assertEquals(3, stats.getTotalAssertions(), "testAdd 2 个 + testSkipped 1 个");
        assertEquals(1, stats.getEmptyTestMethods(), "testEmpty 方法体为空");
        assertEquals(2, stats.getNoAssertionTestMethods(), "testEmpty + testNoAssertion");
        assertEquals(1, stats.getSkippedTestMethods(), "testSkipped 被 @Disabled 标注");
        assertEquals(0, stats.getParseFailedFileCount());
    }

    // ============ JUnit4 兼容 ============

    @Test
    void analyze_junit4_countsCorrectly() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("proj4"));
        writeTestFile(project, "src/test/java/com/example/UserServiceTest.java", JUNIT4_USER);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(1, stats.getTestClassCount());
        assertEquals(2, stats.getTotalTestMethods());
        assertEquals(2, stats.getTotalAssertions());
        assertEquals(1, stats.getSkippedTestMethods());
        assertEquals(0, stats.getEmptyTestMethods());
        assertEquals(0, stats.getNoAssertionTestMethods());
    }

    // ============ 多文件聚合 ============

    @Test
    void analyze_multipleFiles_aggregates() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("multi"));
        writeTestFile(project, "src/test/java/com/example/CalculatorTest.java", JUNIT5_CALCULATOR);
        writeTestFile(project, "src/test/java/com/example/UserServiceTest.java", JUNIT4_USER);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(2, stats.getTestClassCount());
        assertEquals(6, stats.getTotalTestMethods());
        assertEquals(5, stats.getTotalAssertions());
        assertEquals(1, stats.getEmptyTestMethods());
        assertEquals(2, stats.getNoAssertionTestMethods());
        assertEquals(2, stats.getSkippedTestMethods());
        assertEquals(2, stats.getClassStats().size());
    }

    // ============ 不存在测试目录 ============

    @Test
    void analyze_noTestDir_returnsZeros() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("empty"));
        Files.createDirectories(project.resolve("src/main/java"));

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(0, stats.getTestClassCount());
        assertEquals(0, stats.getTotalTestMethods());
        assertEquals(0, stats.getTotalAssertions());
        assertEquals(0, stats.getParseFailedFileCount());
        assertTrue(stats.getClassStats().isEmpty());
        assertTrue(stats.getParseFailedFiles().isEmpty());
        // 合法的"无测试代码"场景：analysisError 必须为 null，与整体分析失败区分
        assertNull(stats.getAnalysisError(), "测试目录不存在属于合法空统计，不应标记 analysisError");
    }

    // ============ 解析失败不中断 ============

    @Test
    void analyze_parseFailureFile_recordedAndSkipped() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("bad"));
        writeTestFile(project, "src/test/java/com/example/GoodTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class GoodTest {
                    @Test
                    void ok() { assertEquals(1, 1); }
                }
                """);
        writeTestFile(project, "src/test/java/com/example/BrokenTest.java",
                "public class BrokenTest { this is not valid java !!! ");

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(1, stats.getTestClassCount(), "好文件仍应被统计");
        assertEquals(1, stats.getTotalTestMethods());
        assertEquals(1, stats.getTotalAssertions());
        assertEquals(1, stats.getParseFailedFileCount(), "坏文件应被记录为解析失败");
        assertEquals(1, stats.getParseFailedFiles().size());
        assertTrue(stats.getParseFailedFiles().get(0).contains("BrokenTest.java"));
    }

    // ============ 注释/字符串中的 assert 不误判 ============

    @Test
    void analyze_assertInCommentOrString_notCounted() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("comment"));
        writeTestFile(project, "src/test/java/com/example/CommentTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class CommentTest {
                    @Test
                    void real() {
                        assertEquals(1, 1);
                    }
                    @Test
                    void fake() {
                        // Assert.assertTrue(false)
                        /* assertEquals(2, 2) */
                        String s = "Assertions.assertEquals";
                        System.out.println(s);
                    }
                    @Test
                    void customAssert() {
                        myAssertHelper();
                    }
                    private void myAssertHelper() {}
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(3, stats.getTotalTestMethods());
        assertEquals(1, stats.getTotalAssertions(), "只有 real 中的真实断言被计数");
        assertEquals(2, stats.getNoAssertionTestMethods(), "fake 与 customAssert 无真实断言");
    }

    // ============ assertThrows 算作断言 ============

    @Test
    void analyze_assertThrows_countedAsAssertion() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("throws"));
        writeTestFile(project, "src/test/java/com/example/ThrowsTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                public class ThrowsTest {
                    @Test
                    void t() {
                        assertThrows(RuntimeException.class, () -> { throw new RuntimeException(); });
                    }
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(1, stats.getTotalAssertions(), "assertThrows 应识别为断言");
        assertEquals(0, stats.getNoAssertionTestMethods());
    }

    // ============ 全限定名 @Test 注解识别 ============

    @Test
    void analyze_fqnTestAnnotation_recognized() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("fqn"));
        writeTestFile(project, "src/test/java/com/example/FqnTest.java", """
                package com.example;
                public class FqnTest {
                    @org.junit.jupiter.api.Test
                    void m() {
                        org.junit.jupiter.api.Assertions.assertTrue(true);
                    }
                    @org.junit.Test
                    void old() {
                        org.junit.Assert.assertNotNull("x");
                    }
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(2, stats.getTotalTestMethods(), "JUnit4 与 JUnit5 全限定名 @Test 都应识别");
        assertEquals(2, stats.getTotalAssertions());
    }

    // ============ 非测试辅助类不计入 ============

    @Test
    void analyze_helperClassNotCounted() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("helper"));
        writeTestFile(project, "src/test/java/com/example/TestHelper.java", """
                package com.example;
                public class TestHelper {
                    public void helper() {}
                }
                """);
        writeTestFile(project, "src/test/java/com/example/RealTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                public class RealTest {
                    @Test
                    void r() { assertTrue(true); }
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(1, stats.getTestClassCount(), "无 @Test 的辅助类不计入测试类数量");
        assertEquals(1, stats.getTotalTestMethods());
        assertEquals(1, stats.getClassStats().size());
        assertEquals("RealTest", stats.getClassStats().get(0).getClassName());
    }

    // ============ 被跳过的方法仍计入测试方法总数 ============

    @Test
    void analyze_skippedMethod_stillCountsAsTestMethod() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("skip"));
        writeTestFile(project, "src/test/java/com/example/SkipTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.Disabled;
                public class SkipTest {
                    @Test
                    @Disabled
                    void a() {}
                    @Test
                    @Disabled("原因")
                    void b() {}
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(2, stats.getTotalTestMethods(), "被跳过的方法仍是测试方法");
        assertEquals(2, stats.getSkippedTestMethods());
        assertEquals(2, stats.getEmptyTestMethods());
    }

    // ============ 中间目录 src 为符号链接时拒绝遍历 ============

    @Test
    void analyze_srcIsSymlink_rejected() throws IOException {
        Path realTestRoot = Files.createTempDirectory("real-src");
        writeTestFile(realTestRoot, "test/java/com/example/AnyTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                public class AnyTest {
                    @Test
                    void t() {}
                }
                """);

        Path project = Files.createDirectory(tempDir.resolve("sym-src"));
        Path srcLink = project.resolve("src");
        try {
            Files.createSymbolicLink(srcLink, realTestRoot);
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "当前系统不支持创建符号链接，跳过测试: " + e.getMessage());
            return;
        }
        // 校验 srcLink 是符号链接且 NOFOLLOW_LINKS 识别出来（否则 assertPathWithinProject 无效）
        boolean symlinkDetected = Files.isSymbolicLink(srcLink)
                && !Files.isDirectory(srcLink, LinkOption.NOFOLLOW_LINKS);
        Assumptions.assumeTrue(symlinkDetected,
                "当前 JVM 无法通过 NOFOLLOW_LINKS 识别中间目录符号链接，跳过测试");

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(0, stats.getTestClassCount(), "src 为指向外部的符号链接时应拒绝遍历");
        assertEquals(0, stats.getTotalTestMethods());
    }

    // ============ 中间目录 src/test 为符号链接时拒绝遍历 ============

    @Test
    void analyze_srcTestIsSymlink_rejected() throws IOException {
        Path realTestRoot = Files.createTempDirectory("real-test");
        writeTestFile(realTestRoot, "java/com/example/AnyTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                public class AnyTest {
                    @Test
                    void t() {}
                }
                """);

        Path project = Files.createDirectory(tempDir.resolve("sym-test"));
        Files.createDirectories(project.resolve("src"));
        Path testLink = project.resolve("src").resolve("test");
        try {
            Files.createSymbolicLink(testLink, realTestRoot);
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "当前系统不支持创建符号链接，跳过测试: " + e.getMessage());
            return;
        }
        boolean symlinkDetected = Files.isSymbolicLink(testLink)
                && !Files.isDirectory(testLink, LinkOption.NOFOLLOW_LINKS);
        Assumptions.assumeTrue(symlinkDetected,
                "当前 JVM 无法通过 NOFOLLOW_LINKS 识别中间目录符号链接，跳过测试");

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(0, stats.getTestClassCount(), "src/test 为指向外部的符号链接时应拒绝遍历");
        assertEquals(0, stats.getTotalTestMethods());
    }

    // ============ TestNG @Test 不计入 JUnit ============

    @Test
    void analyze_testngTest_notCounted() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("testng"));
        writeTestFile(project, "src/test/java/com/example/TestngTest.java", """
                package com.example;
                import org.testng.annotations.Test;
                import org.testng.Assert;

                public class TestngTest {
                    @Test
                    public void testNgMethod() {
                        Assert.assertEquals(1, 1);
                    }
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(0, stats.getTestClassCount(), "TestNG @Test 不应被误判为 JUnit 测试类");
        assertEquals(0, stats.getTotalTestMethods(), "TestNG @Test 不应被计入 JUnit 测试方法数");
        // Assertions 调用也不应计入（PromptBuilder 不认 TestNG Assert），但此处只要测试方法数为 0 即够
    }

    // ============ TestNG 通配符 import 也应排除 ============

    @Test
    void analyze_testngWildcardImport_notCounted() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("testngwc"));
        writeTestFile(project, "src/test/java/com/example/WcTest.java", """
                package com.example;
                import org.testng.annotations.*;
                public class WcTest {
                    @Test
                    public void t() {}
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(0, stats.getTestClassCount(),
                "import org.testng.annotations.* 后简单名 @Test 不应被计为 JUnit");
    }

    // ============ 自定义 @interface Test 不计入 ============

    @Test
    void analyze_customTestAnnotation_notCounted() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("custom"));
        writeTestFile(project, "src/test/java/com/example/CustomTest.java", """
                package com.example;
                public @interface Test { }
                public class CustomTest {
                    @Test
                    public void myTest() {}
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(0, stats.getTestClassCount(),
                "同包自定义 @interface Test 不应被误判为 JUnit @Test");
        assertEquals(0, stats.getTotalTestMethods());
    }

    // ============ 自定义 @interface Ignore 不计入跳过 ============

    @Test
    void analyze_customIgnore_notCountedAsSkipped() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("customskip"));
        writeTestFile(project, "src/test/java/com/example/CustomSkipTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                public @interface Ignore { }
                public class CustomSkipTest {
                    @Test
                    @Ignore
                    public void t() {}
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(1, stats.getTotalTestMethods(), "自定义 @Ignore 不应影响 @Test 的识别");
        assertEquals(0, stats.getSkippedTestMethods(),
                "自定义 @Ignore 不应被计为跳过测试（只有 JUnit org.junit.Ignore 才算）");
    }

    // ============ 无 import 的简单名 @Test 不计入 ============

    @Test
    void analyze_noImportSimpleTest_notCounted() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("noimport"));
        writeTestFile(project, "src/test/java/com/example/NoImportTest.java", """
                package com.example;
                public class NoImportTest {
                    @Test
                    void m() {}
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(0, stats.getTotalTestMethods(),
                "无 import 来源的简单名 @Test 不应被计为 JUnit（可能是自定义注解）");
    }

    // ============ JUnit5 显式 import 后简单名 @Test 正确计数 ============

    @Test
    void analyze_junit5Import_simpleTestCounted() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("j5imp"));
        writeTestFile(project, "src/test/java/com/example/J5Test.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.Disabled;
                public class J5Test {
                    @Test
                    void a() {}
                    @Test
                    @Disabled
                    void b() {}
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(2, stats.getTotalTestMethods(),
                "显式 import org.junit.jupiter.api.Test 后简单名 @Test 应正确计数");
        assertEquals(1, stats.getSkippedTestMethods(),
                "显式 import org.junit.jupiter.api.Disabled 后简单名 @Disabled 应正确计数");
    }

    // ============ JUnit4 通配符 import 后简单名 @Test 正确计数 ============

    @Test
    void analyze_junit4Wildcard_simpleTestCounted() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("j4wc"));
        writeTestFile(project, "src/test/java/com/example/J4Test.java", """
                package com.example;
                import org.junit.*;
                public class J4Test {
                    @Test
                    void a() {}
                    @Test
                    @Ignore
                    void b() {}
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(2, stats.getTotalTestMethods(),
                "import org.junit.* 后简单名 @Test 应正确计数");
        assertEquals(1, stats.getSkippedTestMethods(),
                "import org.junit.* 同时覆盖 @Ignore");
    }

    // ============ 空参数 ============

    @Test
    void analyze_nullProjectDir_throws() {
        assertThrows(IllegalArgumentException.class, () -> TestQualityAnalyzer.analyze(null));
    }

    // ============ 全限定名 @Ignore / @Disabled 识别 ============

    @Test
    void analyze_fqnSkipAnnotation_recognized() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("fqnskip"));
        writeTestFile(project, "src/test/java/com/example/FqnSkipTest.java", """
                package com.example;
                public class FqnSkipTest {
                    @org.junit.jupiter.api.Test
                    @org.junit.jupiter.api.Disabled
                    void a() {}
                    @org.junit.Test
                    @org.junit.Ignore
                    void b() {}
                }
                """);

        TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

        assertEquals(2, stats.getTotalTestMethods());
        assertEquals(2, stats.getSkippedTestMethods());
    }

    // ============ 6.2 加固：单文件统计阶段异常不中断其他文件 ============

    private static final String GOOD_SIMPLE = """
            package com.example;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class GoodTest { @Test void ok() { assertEquals(1, 1); } }
            """;

    private static final String EXPLOSIVE = """
            package com.example;
            import org.junit.jupiter.api.Test;
            public class ExplosiveTest { @Test void boom() {} }
            """;

    @Test
    void analyze_singleFileStatisticsException_recordedAndContinues() throws IOException {
        // 模拟：某文件在 AST 解析成功后，统计阶段（countAssertions）抛出 RuntimeException
        Path project = Files.createDirectory(tempDir.resolve("statsex"));
        writeTestFile(project, "src/test/java/com/example/GoodTest.java", GOOD_SIMPLE);
        writeTestFile(project, "src/test/java/com/example/ExplosiveTest.java", EXPLOSIVE);

        try (MockedStatic<PromptBuilder> mocked = mockStatic(PromptBuilder.class)) {
            mocked.when(() -> PromptBuilder.countAssertions(any())).thenAnswer(inv -> {
                String code = inv.getArgument(0);
                if (code != null && code.contains("Explosive")) {
                    throw new RuntimeException("模拟统计阶段异常");
                }
                return 1;
            });

            TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

            // 好文件仍被统计（mocked countAssertions 返回 1）
            assertEquals(1, stats.getTestClassCount(), "单文件异常不应中断其他文件的统计");
            assertEquals(1, stats.getTotalAssertions());
            // 异常文件被记录为解析失败
            assertEquals(1, stats.getParseFailedFileCount());
            assertTrue(stats.getParseFailedFiles().get(0).contains("ExplosiveTest.java"));
            // 单文件异常不属于整体分析失败
            assertNull(stats.getAnalysisError());
        }
    }

    // ============ 6.2 加固：整体遍历异常设置 analysisError，不伪装成无测试代码 ============

    @Test
    void analyze_walkIOException_setsAnalysisError() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("walkerr"));
        writeTestFile(project, "src/test/java/com/example/GoodTest.java", GOOD_SIMPLE);

        // 默认 mockStatic：isSymbolicLink / exists 均返回 false，
        // 恰好使 TestFileManager.assertPathWithinProject 跳过 toRealPath 校验且不抛异常；
        // 仅 stub isDirectory 让测试目录被识别为存在，再让 Files.walk 抛出 IOException。
        try (MockedStatic<Files> mocked = mockStatic(Files.class)) {
            mocked.when(() -> Files.isDirectory(any(Path.class), any(LinkOption[].class)))
                    .thenReturn(true);
            mocked.when(() -> Files.walk(any(Path.class)))
                    .thenThrow(new IOException("模拟遍历失败"));

            TestQualityStaticStats stats = TestQualityAnalyzer.analyze(project);

            // 整体遍历失败必须标记 analysisError，绝不能静默返回无错误标记的全零统计
            assertNotNull(stats.getAnalysisError(), "遍历失败应设置 analysisError");
            assertTrue(stats.getAnalysisError().contains("遍历"),
                    "analysisError 应体现遍历失败语义");
        }
    }
}
