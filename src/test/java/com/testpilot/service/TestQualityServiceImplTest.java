package com.testpilot.service;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.CoverageMetric;
import com.testpilot.dto.CoverageResult;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.MethodInfo;
import com.testpilot.dto.MethodParameter;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.dto.TestGenerationRequest;
import com.testpilot.dto.TestQualityResult;
import com.testpilot.dto.TestQualityStaticStats;
import com.testpilot.dto.QualityReport;
import com.testpilot.service.impl.TestGenerationServiceImpl;
import com.testpilot.service.impl.TestQualityServiceImpl;
import com.testpilot.utils.ProjectExecutionLock;
import com.testpilot.utils.TestQualityAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * TestQualityServiceImpl 单元测试（6.2）。
 * <p>
 * 覆盖：8 种执行/覆盖率状态与静态统计的组合、projectId 空校验、并发评估互斥（409）、
 * 以及评估持锁期间测试生成写入被锁拦截（跨服务锁共享）。
 */
class TestQualityServiceImplTest {

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private CoverageService coverageService;
    private LLMService llmService;
    private ProjectExecutionLock lock;
    private TestQualityServiceImpl service;

    private static final String GOOD_TEST = """
            package com.example;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class CalculatorTest {
                @Test void testAdd() { assertEquals(2, 1 + 1); }
            }
            """;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        coverageService = mock(CoverageService.class);
        llmService = mock(LLMService.class);
        lock = new ProjectExecutionLock();
        service = new TestQualityServiceImpl(projectService, coverageService, lock);
    }

    // ============ 辅助方法 ============

    private Path createMavenProject(String name, String... testFiles) throws IOException {
        Path project = Files.createDirectory(tempDir.resolve(name));
        for (int i = 0; i < testFiles.length; i += 2) {
            Path file = project.resolve(testFiles[i]);
            Files.createDirectories(file.getParent());
            Files.writeString(file, testFiles[i + 1], StandardCharsets.UTF_8);
        }
        return project;
    }

    private CoverageResult coverage(String execStatus, String covStatus,
                                    int total, int passed, int failed,
                                    List<CoverageMetric> metrics, String covError) {
        TestExecutionResult ter = new TestExecutionResult(
                "p1", "PASSED".equals(execStatus), execStatus, 0,
                total, passed, failed, 0, 100L, "", null);
        return new CoverageResult("p1", ter, covStatus, metrics, covError);
    }

    private List<CoverageMetric> lineMetric() {
        return List.of(new CoverageMetric("LINE", true, 10, 90, 90.0));
    }

    // ============ 1. 正常测试通过并生成覆盖率 ============

    @Test
    void evaluate_passedWithCoverage_returnsCombined() throws Exception {
        Path project = createMavenProject("ok",
                "src/test/java/com/example/CalculatorTest.java", GOOD_TEST);
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);
        CoverageResult cov = coverage("PASSED", "COVERAGE_GENERATED", 1, 1, 0, lineMetric(), null);
        when(coverageService.runCoverage("p1")).thenReturn(cov);

        TestQualityResult result = service.evaluate("p1");

        assertEquals("p1", result.getProjectId());
        assertSame(cov, result.getCoverageResult());
        assertEquals("PASSED", result.getCoverageResult().getTestExecutionResult().getStatus());
        assertEquals("COVERAGE_GENERATED", result.getCoverageResult().getCoverageStatus());
        // 静态统计来自真实分析
        assertEquals(1, result.getStaticStats().getTestClassCount());
        assertEquals(1, result.getStaticStats().getTotalTestMethods());
        assertNull(result.getStaticStats().getAnalysisError());
    }

    // ============ 2. 测试失败但仍生成覆盖率 ============

    @Test
    void evaluate_failedButCoverageGenerated_returnsBoth() throws Exception {
        Path project = createMavenProject("fail",
                "src/test/java/com/example/CalculatorTest.java", GOOD_TEST);
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);
        CoverageResult cov = coverage("FAILED", "COVERAGE_GENERATED", 2, 1, 1, lineMetric(), null);
        when(coverageService.runCoverage("p1")).thenReturn(cov);

        TestQualityResult result = service.evaluate("p1");

        assertEquals("FAILED", result.getCoverageResult().getTestExecutionResult().getStatus());
        assertEquals(1, result.getCoverageResult().getTestExecutionResult().getFailedTests());
        assertEquals("COVERAGE_GENERATED", result.getCoverageResult().getCoverageStatus());
        assertNotNull(result.getCoverageResult().getMetrics());
        // 覆盖率不丢弃，静态统计也不丢弃
        assertEquals(1, result.getStaticStats().getTotalTestMethods());
    }

    // ============ 3. 编译失败，仍返回静态统计 ============

    @Test
    void evaluate_compileError_stillReturnsStaticStats() throws Exception {
        Path project = createMavenProject("compile",
                "src/test/java/com/example/CalculatorTest.java", GOOD_TEST);
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);
        CoverageResult cov = coverage("COMPILE_ERROR", "NO_COVERAGE", 0, 0, 0, List.of(), "编译失败");
        when(coverageService.runCoverage("p1")).thenReturn(cov);

        TestQualityResult result = service.evaluate("p1");

        assertEquals("COMPILE_ERROR", result.getCoverageResult().getTestExecutionResult().getStatus());
        assertEquals("NO_COVERAGE", result.getCoverageResult().getCoverageStatus());
        assertTrue(result.getCoverageResult().getMetrics().isEmpty());
        // 编译失败不影响静态分析，静态统计仍应返回
        assertEquals(1, result.getStaticStats().getTestClassCount());
        assertEquals(1, result.getStaticStats().getTotalTestMethods());
    }

    // ============ 4. Maven 超时 ============

    @Test
    void evaluate_mavenTimeout_returnsCombined() throws Exception {
        Path project = createMavenProject("timeout",
                "src/test/java/com/example/CalculatorTest.java", GOOD_TEST);
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);
        CoverageResult cov = coverage("TIMEOUT", "NO_COVERAGE", 0, 0, 0, List.of(), "执行超时");
        when(coverageService.runCoverage("p1")).thenReturn(cov);

        TestQualityResult result = service.evaluate("p1");

        assertEquals("TIMEOUT", result.getCoverageResult().getTestExecutionResult().getStatus());
        assertEquals("NO_COVERAGE", result.getCoverageResult().getCoverageStatus());
        // 超时不影响静态分析
        assertEquals(1, result.getStaticStats().getTotalTestMethods());
    }

    // ============ 5. 无 Surefire 报告 ============

    @Test
    void evaluate_noSurefireReports_returnsCombined() throws Exception {
        Path project = createMavenProject("noreports",
                "src/test/java/com/example/CalculatorTest.java", GOOD_TEST);
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);
        CoverageResult cov = coverage("NO_REPORTS", "NO_COVERAGE", 0, 0, 0, List.of(), "未找到测试报告");
        when(coverageService.runCoverage("p1")).thenReturn(cov);

        TestQualityResult result = service.evaluate("p1");

        assertEquals("NO_REPORTS", result.getCoverageResult().getTestExecutionResult().getStatus());
        assertEquals(1, result.getStaticStats().getTotalTestMethods());
    }

    // ============ 6. JaCoCo 报告缺失 ============

    @Test
    void evaluate_jacocoReportMissing_returnsCombined() throws Exception {
        Path project = createMavenProject("nocov",
                "src/test/java/com/example/CalculatorTest.java", GOOD_TEST);
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);
        CoverageResult cov = coverage("PASSED", "NO_REPORT", 1, 1, 0, List.of(),
                "jacoco.xml 无有效覆盖率计数器");
        when(coverageService.runCoverage("p1")).thenReturn(cov);

        TestQualityResult result = service.evaluate("p1");

        assertEquals("NO_REPORT", result.getCoverageResult().getCoverageStatus());
        assertNotNull(result.getCoverageResult().getCoverageError());
        // 覆盖率缺失不影响静态统计
        assertEquals(1, result.getStaticStats().getTotalTestMethods());
    }

    // ============ 7. 静态分析部分文件解析失败 ============

    @Test
    void evaluate_staticAnalysisPartialFailure_preservesCoverage() throws Exception {
        Path project = createMavenProject("partial",
                "src/test/java/com/example/GoodTest.java", GOOD_TEST,
                "src/test/java/com/example/BrokenTest.java", "public class BrokenTest { !!! not java ");
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);
        CoverageResult cov = coverage("PASSED", "COVERAGE_GENERATED", 1, 1, 0, lineMetric(), null);
        when(coverageService.runCoverage("p1")).thenReturn(cov);

        TestQualityResult result = service.evaluate("p1");

        // coverageResult 原样保留
        assertSame(cov, result.getCoverageResult());
        // 好文件仍被统计
        assertEquals(1, result.getStaticStats().getTestClassCount());
        assertEquals(1, result.getStaticStats().getTotalTestMethods());
        // 坏文件被记录为解析失败，不中断整体
        assertEquals(1, result.getStaticStats().getParseFailedFileCount());
        assertTrue(result.getStaticStats().getParseFailedFiles().get(0).contains("BrokenTest.java"));
        // 部分失败不是整体失败
        assertNull(result.getStaticStats().getAnalysisError());
    }

    // ============ 8. 静态分析整体失败但 coverageResult 仍保留 ============

    @Test
    void evaluate_staticAnalysisWholeFailure_preservesCoverageResult() throws Exception {
        Path project = createMavenProject("wholeerr",
                "src/test/java/com/example/CalculatorTest.java", GOOD_TEST);
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);
        CoverageResult cov = coverage("PASSED", "COVERAGE_GENERATED", 1, 1, 0, lineMetric(), null);
        when(coverageService.runCoverage("p1")).thenReturn(cov);

        // 模拟静态分析整体异常：返回带 analysisError 但含部分统计的结果
        TestQualityStaticStats statsWithError = new TestQualityStaticStats();
        statsWithError.setTestClassCount(1);
        statsWithError.setTotalTestMethods(2);
        statsWithError.setAnalysisError("模拟整体分析异常");

        try (MockedStatic<TestQualityAnalyzer> mocked = mockStatic(TestQualityAnalyzer.class)) {
            mocked.when(() -> TestQualityAnalyzer.analyze(any())).thenReturn(statsWithError);

            TestQualityResult result = service.evaluate("p1");

            // coverageResult 绝不因静态分析失败而丢弃
            assertSame(cov, result.getCoverageResult());
            assertNotNull(result.getStaticStats().getAnalysisError());
            // 部分统计仍保留
            assertEquals(2, result.getStaticStats().getTotalTestMethods());
        }
    }

    // ============ 9. projectId 为空 ============

    @Test
    void evaluate_blankProjectId_throws400() {
        BusinessException e1 = assertThrows(BusinessException.class, () -> service.evaluate(null));
        assertEquals(400, e1.getCode());
        BusinessException e2 = assertThrows(BusinessException.class, () -> service.evaluate("   "));
        assertEquals(400, e2.getCode());
    }

    // ============ 10. 同一项目并发评估，第二次返回 409 ============

    @Test
    void evaluate_concurrentSameProject_secondRejected409() throws Exception {
        Path project = createMavenProject("conc",
                "src/test/java/com/example/CalculatorTest.java", GOOD_TEST);
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(coverageService.runCoverage("p1")).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return coverage("PASSED", "COVERAGE_GENERATED", 1, 1, 0, lineMetric(), null);
        });

        Thread t = new Thread(() -> service.evaluate("p1"));
        t.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "评估线程应已持锁并进入 runCoverage");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.evaluate("p1"));
        assertEquals(409, ex.getCode());

        release.countDown();
        t.join(5000);
    }

    // ============ 11. 评估持锁期间，测试生成写入不能绕过项目锁 ============

    @Test
    void evaluate_holdingLock_blocksTestGenerationWrite() throws Exception {
        // 写入 JUnit4 pom，使 JUnitDetector 能识别版本（生成流程在持锁前需完成检测）
        Path project = createMavenProject("genblock");
        Files.writeString(project.resolve("pom.xml"), JUNIT4_POM, StandardCharsets.UTF_8);
        when(projectService.getMavenProjectDir("p1")).thenReturn(project);
        when(projectService.getProjectDir("p1")).thenReturn(project);
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(buildCalculatorContext());

        // 评估线程持锁并阻塞在 runCoverage
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(coverageService.runCoverage("p1")).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return coverage("PASSED", "COVERAGE_GENERATED", 1, 1, 0, lineMetric(), null);
        });

        Thread evalThread = new Thread(() -> service.evaluate("p1"));
        evalThread.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "评估线程应已持锁");

        // 测试生成服务与评估共享同一把锁
        TestGenerationServiceImpl genService =
                new TestGenerationServiceImpl(projectService, llmService, lock);
        when(llmService.chat(anyString())).thenReturn("```java\npackage com.example;\n"
                + "import org.junit.Test;\nimport org.junit.Assert;\n"
                + "public class CalculatorTest { @Test public void t(){ Assert.assertEquals(1,1);} }\n```");

        // 评估持锁期间，生成写入应被锁拦截（409），不能绕过锁写入 src/test/java
        BusinessException ex = assertThrows(BusinessException.class,
                () -> genService.generateTest("p1", buildRequest()));
        assertEquals(409, ex.getCode());

        // 锁被拦截，不应写入任何文件
        assertFalse(Files.exists(project.resolve("src/test/java/com/example/CalculatorTest.java")));

        release.countDown();
        evalThread.join(5000);
    }

    private static final String JUNIT4_POM =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                    + "  <modelVersion>4.0.0</modelVersion>\n"
                    + "  <groupId>com.example</groupId><artifactId>demo</artifactId><version>1.0</version>\n"
                    + "  <dependencies>\n    <dependency>\n      <groupId>junit</groupId>\n"
                    + "      <artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope>\n"
                    + "    </dependency>\n  </dependencies>\n</project>\n";

    private MethodContext buildCalculatorContext() {
        MethodParameter p = new MethodParameter("a", "int");
        MethodParameter p2 = new MethodParameter("b", "int");
        MethodInfo methodInfo = new MethodInfo("add", "int", "public",
                false, List.of(p, p2), "public int add(int a, int b) { return a + b; }");
        String classSource = "package com.example;\npublic class Calculator {\n"
                + "  public int add(int a, int b) { return a + b; }\n}";
        return new MethodContext("com.example", "Calculator",
                "src/main/java/com/example/Calculator.java", classSource, methodInfo);
    }

    private TestGenerationRequest buildRequest() {
        TestGenerationRequest req = new TestGenerationRequest();
        req.setClassName("Calculator");
        req.setMethodName("add");
        req.setParamTypes(List.of("int", "int"));
        return req;
    }

    // ============ 6.3 qualityReport 集成验证 ============

    @Test
    void evaluate_normal_returnsQualityReport() throws IOException {
        Path projectDir = Files.createDirectory(tempDir.resolve("normal"));
        when(projectService.getMavenProjectDir("p1")).thenReturn(projectDir);

        // 准备 src/test/java 空目录（TestQualityAnalyzer 返回默认全零 stats）
        Path testJava = projectDir.resolve("src").resolve("test").resolve("java");
        Files.createDirectories(testJava);

        TestExecutionResult exec = new TestExecutionResult("p1", true, "PASSED", 0,
                11, 11, 0, 0, 0L, null, null);
        CoverageResult cov = new CoverageResult("p1", exec, "COVERAGE_GENERATED",
                List.of(
                        new CoverageMetric("LINE", true, 20, 80, 80.0),
                        new CoverageMetric("BRANCH", true, 30, 70, 70.0),
                        new CoverageMetric("METHOD", true, 15, 85, 85.0),
                        new CoverageMetric("INSTRUCTION", true, 0, 1000, 90.0),
                        new CoverageMetric("COMPLEXITY", true, 5, 15, null),
                        new CoverageMetric("CLASS", true, 2, 8, 80.0)
                ), null);
        when(coverageService.runCoverage("p1")).thenReturn(cov);

        TestQualityResult result = service.evaluate("p1");

        // 原有字段保持兼容
        assertEquals("p1", result.getProjectId());
        assertNotNull(result.getStaticStats());
        assertNotNull(result.getCoverageResult());

        // 6.3 qualityReport 正常生成
        QualityReport report = result.getQualityReport();
        assertNotNull(report, "qualityReport 不应为 null");
        assertNotNull(report.getOverallScore());
        assertNotNull(report.getGrade());
        assertNotNull(report.getDimensions());
        // 4 个维度
        assertEquals(4, report.getDimensions().size());
        assertTrue(report.getIssues() != null);
    }

    @Test
    void evaluate_compileError_gradeNA_stillHasIssues() throws IOException {
        Path projectDir = Files.createDirectory(tempDir.resolve("compile"));
        when(projectService.getMavenProjectDir("p2")).thenReturn(projectDir);

        TestExecutionResult exec = new TestExecutionResult("p2", false, "COMPILE_ERROR", -1,
                0, 0, 0, 0, 0L, null, null);
        CoverageResult cov = new CoverageResult("p2", exec, "NO_COVERAGE", List.of(), null);
        when(coverageService.runCoverage("p2")).thenReturn(cov);

        TestQualityResult result = service.evaluate("p2");
        QualityReport report = result.getQualityReport();
        assertNotNull(report);
        // 综合分 N/A：运行时维度 0 个
        assertNull(report.getOverallScore());
        assertEquals("N/A", report.getGrade());

        // 但 COMPILE_ERROR issue 仍然生成
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "COMPILE_ERROR".equals(i.getCategory())),
                "COMPILE_ERROR 状态应生成 COMPILE_ERROR 建议");
    }

    @Test
    void evaluate_lockConflict_returns409() throws Exception {
        // 用另一线程先持锁 → Service 在主线程 tryLock 失败 → 409
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            lock.tryLock("p3");
            acquired.countDown();
            try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            lock.unlock("p3");
        }, "holder").start();
        acquired.await(5, TimeUnit.SECONDS);

        try {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.evaluate("p3"));
            assertEquals(409, ex.getCode());
        } finally {
            done.countDown();
        }
    }

    // ============ 12. 锁内操作异常时，finally 必须释放项目锁 ============

    @Test
    void evaluate_runCoverageThrows_lockReleasedAfterException() throws Exception {
        // runCoverage 模拟抛异常：锁内异常 → finally 释放锁 → 评分不执行 → 异常原样抛出
        when(projectService.getMavenProjectDir("p4")).thenReturn(tempDir);
        when(coverageService.runCoverage("p4"))
                .thenThrow(new RuntimeException("模拟执行失败"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.evaluate("p4"));
        assertEquals("模拟执行失败", ex.getMessage());

        // 用另一线程验证锁已释放（避免 ReentrantLock 同线程可重入导致误判）
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<Boolean> acquiredOk = new AtomicReference<>(false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread checker = new Thread(() -> {
            try {
                boolean ok = lock.tryLock("p4");
                acquiredOk.set(ok);
                if (ok) {
                    lock.unlock("p4");
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                acquired.countDown();
            }
        }, "lock-checker");
        checker.start();
        assertTrue(acquired.await(5, TimeUnit.SECONDS), "checker 线程应在超时内完成");
        checker.join(5000);
        assertNull(error.get(), "checker 线程不应抛出异常");
        assertTrue(acquiredOk.get(), "锁内异常后项目锁必须已释放，另一线程应能获取");
    }
}
