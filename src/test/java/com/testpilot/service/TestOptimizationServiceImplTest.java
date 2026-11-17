package com.testpilot.service;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.JUnitVersion;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.MethodInfo;
import com.testpilot.dto.MethodParameter;
import com.testpilot.dto.RepairRecord;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.dto.TestOptimizationRequest;
import com.testpilot.dto.TestOptimizationResult;
import com.testpilot.service.impl.TestOptimizationServiceImpl;
import com.testpilot.utils.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestOptimizationServiceImplTest {

    private TestOptimizationServiceImpl optimizationService;
    private ProjectService projectService;
    private TestExecutionService testExecutionService;
    private LLMService llmService;

    @TempDir
    Path tempDir;

    private static final String SOURCE_RELATIVE = "src/main/java/com/example/Calculator.java";

    @BeforeEach
    void setUp() throws IOException {
        projectService = mock(ProjectService.class);
        testExecutionService = mock(TestExecutionService.class);
        llmService = mock(LLMService.class);
        optimizationService = new TestOptimizationServiceImpl(projectService, testExecutionService, llmService, new com.testpilot.utils.ProjectExecutionLock());

        // 写入 JUnit 4 pom.xml 供 JUnitDetector 检测
        writePom(tempDir);
    }

    private void writePom(Path dir) throws IOException {
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n  <artifactId>demo</artifactId>\n  <version>1.0</version>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>junit</groupId><artifactId>junit</artifactId>"
                + "<version>4.13.2</version><scope>test</scope></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        Files.writeString(dir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
    }

    private MethodContext buildContext() {
        MethodParameter p1 = new MethodParameter("a", "int");
        MethodParameter p2 = new MethodParameter("b", "int");
        MethodInfo methodInfo = new MethodInfo("add", "int", "public",
                false, List.of(p1, p2), "public int add(int a, int b) { return a + b; }");
        String classSource = "package com.example;\npublic class Calculator {\n"
                + "  public int add(int a, int b) { return a + b; }\n}";
        return new MethodContext("com.example", "Calculator", SOURCE_RELATIVE, classSource, methodInfo);
    }

    private TestOptimizationRequest buildRequest() {
        TestOptimizationRequest req = new TestOptimizationRequest();
        req.setClassName("Calculator");
        req.setMethodName("add");
        req.setParamTypes(List.of("int", "int"));
        req.setMaxRetries(2);
        return req;
    }

    private Path writeExistingTest(String code) throws IOException {
        Path testFile = PromptBuilder.resolveTestFilePath(tempDir, buildContext());
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, code, StandardCharsets.UTF_8);
        return testFile;
    }

    private TestExecutionResult result(String status, boolean success, int exitCode,
                                       int failed, String output) {
        return new TestExecutionResult("pid", success, status, exitCode,
                3, 3 - failed, failed, 0, 1000L, output, "");
    }

    private String validJUnit4Test() {
        return "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    Calculator c = new Calculator();\n"
                + "    Assert.assertEquals(3, c.add(1, 2));\n"
                + "  }\n"
                + "}\n";
    }

    private void setupCommonMocks() {
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(buildContext());
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);
        when(projectService.getMavenProjectDir(anyString())).thenReturn(tempDir);
    }

    // 1. 初次执行通过，不调用 LLM
    @Test
    void optimize_initialPassed_doesNotCallLLM() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("PASSED", true, 0, 0, ""));

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        assertTrue(r.isOptimizationSuccess());
        assertEquals(0, r.getRetryCount());
        verify(llmService, never()).chat(anyString());
    }

    // 2. 初次编译失败，调用 LLM 修复
    @Test
    void optimize_compileError_callsLLM() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        TestExecutionResult compileError = result("COMPILE_ERROR", false, 1, 0,
                "[ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol\n[ERROR] symbol: class Foo");
        TestExecutionResult passed = result("PASSED", true, 0, 0, "");
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(compileError)
                .thenReturn(passed);
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        assertTrue(r.isOptimizationSuccess());
        assertEquals(1, r.getRetryCount());
        verify(llmService, times(1)).chat(anyString());
    }

    // 3. 初次测试失败，调用 LLM 修复
    @Test
    void optimize_testFailure_callsLLM() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        TestExecutionResult failed = result("FAILED", false, 1, 1, "");
        TestExecutionResult passed = result("PASSED", true, 0, 0, "");
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(failed)
                .thenReturn(passed);
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        assertTrue(r.isOptimizationSuccess());
        assertEquals(1, r.getRetryCount());
        verify(llmService, times(1)).chat(anyString());
    }

    // 4. 修复一次后通过
    @Test
    void optimize_repairedOnce_passes() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("FAILED", false, 1, 1, ""))
                .thenReturn(result("PASSED", true, 0, 0, ""));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());
        assertTrue(r.isOptimizationSuccess());
        assertEquals(1, r.getRetryCount());
    }

    // 5. 修复两次仍失败，正确停止
    @Test
    void optimize_twoRetriesStillFail_stops() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("FAILED", false, 1, 1, ""))
                .thenReturn(result("FAILED", false, 1, 1, ""))
                .thenReturn(result("FAILED", false, 1, 1, ""));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());
        assertFalse(r.isOptimizationSuccess());
        assertEquals(2, r.getRetryCount());
        assertEquals(2, r.getRepairRecords().size());
        verify(llmService, times(2)).chat(anyString());
    }

    // 5a. 初始 COMPILE_ERROR，修复后 PASSED：finalExecutionResult 必须为最后一次执行结果(PASSED)，
    //     initialExecutionResult 保留第一次结果(COMPILE_ERROR)，optimizationSuccess 与 final 一致
    @Test
    void optimize_compileErrorThenPassed_finalResultIsLastExecution() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        TestExecutionResult compileError = result("COMPILE_ERROR", false, 1, 0,
                "[ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol\n"
                        + "[ERROR] symbol: class Foo");
        TestExecutionResult passed = result("PASSED", true, 0, 0, "BUILD SUCCESS");
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(compileError)
                .thenReturn(passed);
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        // 初始执行结果保留
        assertEquals("COMPILE_ERROR", r.getInitialExecutionResult().getStatus(),
                "initialExecutionResult 必须保留第一次执行结果");
        // 最终执行结果必须为最后一次真实执行结果
        assertEquals("PASSED", r.getFinalExecutionResult().getStatus(),
                "finalExecutionResult 必须返回最后一次执行结果(PASSED)");
        // success 与 final 一致
        assertTrue(r.isOptimizationSuccess(), "optimizationSuccess 必须与 finalExecutionResult 一致");
        assertEquals(1, r.getRetryCount());
        // 修复记录中的执行结果也是 PASSED
        assertEquals("PASSED", r.getRepairRecords().get(0).getExecutionResult().getStatus());
    }

    // 5b. 初始 FAILED，修复后 PASSED：finalExecutionResult 为 PASSED，initialExecutionResult 为 FAILED
    @Test
    void optimize_failureThenPassed_finalResultIsPassed() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("FAILED", false, 1, 1, ""))
                .thenReturn(result("PASSED", true, 0, 0, "BUILD SUCCESS"));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        assertEquals("FAILED", r.getInitialExecutionResult().getStatus());
        assertEquals("PASSED", r.getFinalExecutionResult().getStatus());
        assertTrue(r.isOptimizationSuccess());
    }

    // 5c. 多次修复后仍失败：finalExecutionResult 必须为最后一次执行结果(FAILED)，
    //      optimizationSuccess=false 与 final 一致，initialExecutionResult 保留第一次结果
    @Test
    void optimize_twoRetriesStillFail_finalResultIsLastFailure() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("COMPILE_ERROR", false, 1, 0,
                        "[ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol"))
                .thenReturn(result("COMPILE_ERROR", false, 1, 0,
                        "[ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol"))
                .thenReturn(result("FAILED", false, 1, 1, ""));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        // 初始执行结果保留
        assertEquals("COMPILE_ERROR", r.getInitialExecutionResult().getStatus(),
                "initialExecutionResult 必须保留第一次执行结果");
        // 最终执行结果必须为最后一次真实执行结果(FAILED)
        assertEquals("FAILED", r.getFinalExecutionResult().getStatus(),
                "finalExecutionResult 必须返回最后一次执行结果(FAILED)");
        // success 与 final 一致：FAILED 不应为 success
        assertFalse(r.isOptimizationSuccess(), "optimizationSuccess 必须与 finalExecutionResult 一致");
        assertEquals(2, r.getRetryCount());
        assertEquals(2, r.getRepairRecords().size());
        // 第二条修复记录的执行结果是最后一次 FAILED
        assertEquals("FAILED", r.getRepairRecords().get(1).getExecutionResult().getStatus());
    }

    // 6. Maven 启动失败，不调用 LLM
    @Test
    void optimize_mavenError_doesNotCallLLM() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("MAVEN_ERROR", false, -1, 0, "cannot start maven"));

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());
        assertFalse(r.isOptimizationSuccess());
        assertEquals(0, r.getRetryCount());
        verify(llmService, never()).chat(anyString());
    }

    // 7. Maven 超时，不调用 LLM
    @Test
    void optimize_timeout_doesNotCallLLM() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("TIMEOUT", false, -1, 0, ""));

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());
        assertFalse(r.isOptimizationSuccess());
        assertEquals(0, r.getRetryCount());
        verify(llmService, never()).chat(anyString());
    }

    // 8. LLM 返回空代码或无效代码时，安全停止
    @Test
    void optimize_llmReturnsInvalidCode_stopsSafely() throws IOException {
        setupCommonMocks();
        Path testFile = writeExistingTest(validJUnit4Test());
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("COMPILE_ERROR", false, 1, 0,
                        "[ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol"));
        // LLM 返回无效内容（没有 @Test）
        when(llmService.chat(anyString())).thenReturn("```java\npackage com.example;\nclass NotATest {}\n```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());
        assertFalse(r.isOptimizationSuccess());
        // 无效代码不应覆盖原文件
        String content = Files.readString(testFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("testAdd"));
        // 记录中标记未修复
        RepairRecord rec = r.getRepairRecords().get(0);
        assertFalse(rec.isRepaired());
    }

    // 9. 修复后测试代码保存正确
    @Test
    void optimize_repairedCodeSavedCorrectly() throws IOException {
        setupCommonMocks();
        Path testFile = writeExistingTest(validJUnit4Test());
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("COMPILE_ERROR", false, 1, 0,
                        "[ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol"))
                .thenReturn(result("PASSED", true, 0, 0, ""));
        String repaired = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testFixed() {\n"
                + "    Calculator c = new Calculator();\n"
                + "    Assert.assertEquals(5, c.add(2, 3));\n"
                + "  }\n"
                + "}\n";
        when(llmService.chat(anyString())).thenReturn("```java\n" + repaired + "```");

        optimizationService.optimize("pid", buildRequest());

        String saved = Files.readString(testFile, StandardCharsets.UTF_8);
        assertTrue(saved.contains("testFixed"));
        assertTrue(saved.contains("c.add(2, 3)"));
    }

    // 10. 修复过程中发生异常时，能够保护原始测试代码
    @Test
    void optimize_exceptionDuringRepair_restoresOriginal() throws IOException {
        setupCommonMocks();
        Path testFile = writeExistingTest(validJUnit4Test());
        String original = Files.readString(testFile, StandardCharsets.UTF_8);

        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("COMPILE_ERROR", false, 1, 0,
                        "[ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol"))
                .thenThrow(new RuntimeException("Maven exploded"));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        optimizationService.optimize("pid", buildRequest());

        // 原始代码应被恢复
        String restored = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(original, restored);
    }

    // 11. JUnit 4 修复 Prompt 正确（不含 JUnit 5 专属 API）
    @Test
    void repairPrompt_junit4_doesNotContainJunit5Api() {
        MethodContext ctx = buildContext();
        String prompt = PromptBuilder.buildRepairPrompt(ctx, JUnitVersion.JUNIT4,
                validJUnit4Test(), null, 1);
        assertTrue(prompt.contains("JUnit 4"));
        assertTrue(prompt.contains("org.junit.Test"));
        assertFalse(prompt.contains("org.junit.jupiter"));
    }

    // 11b. JUnit 5 修复 Prompt 正确
    @Test
    void repairPrompt_junit5_containsCorrectApi() {
        MethodContext ctx = buildContext();
        String prompt = PromptBuilder.buildRepairPrompt(ctx, JUnitVersion.JUNIT5,
                validJUnit4Test(), null, 1);
        assertTrue(prompt.contains("JUnit 5"));
        assertTrue(prompt.contains("org.junit.jupiter.api.Test"));
    }

    // 12. 不允许修复用户的业务源码 —— 被测类源码文件不被修改
    @Test
    void optimize_doesNotModifyBusinessSource() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        // 写入被测类源码
        Path sourceFile = tempDir.resolve(SOURCE_RELATIVE);
        Files.createDirectories(sourceFile.getParent());
        String sourceContent = "package com.example;\npublic class Calculator {\n  public int add(int a, int b) { return a + b; }\n}";
        Files.writeString(sourceFile, sourceContent, StandardCharsets.UTF_8);

        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("FAILED", false, 1, 1, ""))
                .thenReturn(result("PASSED", true, 0, 0, ""));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        optimizationService.optimize("pid", buildRequest());

        assertEquals(sourceContent, Files.readString(sourceFile, StandardCharsets.UTF_8));
    }

    // 13. 修复后再次执行 TIMEOUT：立即停止后续 LLM 修复，并恢复本轮修改前的测试代码
    @Test
    void optimize_repairedThenTimeout_stopsAndRestores() throws IOException {
        setupCommonMocks();
        Path testFile = writeExistingTest(validJUnit4Test());
        String original = validJUnit4Test();

        // 初次 FAILED -> 修复 -> 再次执行 TIMEOUT
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("FAILED", false, 1, 1, ""))
                .thenReturn(result("TIMEOUT", false, -1, 0, ""));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        assertFalse(r.isOptimizationSuccess());
        assertEquals(1, r.getRetryCount());
        // TIMEOUT 后不再调用 LLM
        verify(llmService, times(1)).chat(anyString());
        // 测试代码应被恢复为原始版本
        assertEquals(original, Files.readString(testFile, StandardCharsets.UTF_8));
    }

    // 14. 修复后再次执行 MAVEN_ERROR：立即停止并恢复代码
    @Test
    void optimize_repairedThenMavenError_stopsAndRestores() throws IOException {
        setupCommonMocks();
        Path testFile = writeExistingTest(validJUnit4Test());
        String original = validJUnit4Test();

        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("FAILED", false, 1, 1, ""))
                .thenReturn(result("MAVEN_ERROR", false, -1, 0, "mvn not found"));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        assertFalse(r.isOptimizationSuccess());
        assertEquals(1, r.getRetryCount());
        verify(llmService, times(1)).chat(anyString());
        assertEquals(original, Files.readString(testFile, StandardCharsets.UTF_8));
    }

    // 15. 修复后再次执行 NO_REPORTS：立即停止并恢复代码
    @Test
    void optimize_repairedThenNoReports_stopsAndRestores() throws IOException {
        setupCommonMocks();
        Path testFile = writeExistingTest(validJUnit4Test());
        String original = validJUnit4Test();

        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("FAILED", false, 1, 1, ""))
                .thenReturn(result("NO_REPORTS", false, 0, 0, ""));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        assertFalse(r.isOptimizationSuccess());
        assertEquals(1, r.getRetryCount());
        verify(llmService, times(1)).chat(anyString());
        assertEquals(original, Files.readString(testFile, StandardCharsets.UTF_8));
    }

    // 16. 主源码编译失败不调用 LLM
    @Test
    void optimize_mainSourceCompileError_doesNotCallLLM() throws IOException {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        // 错误来自 src/main/java 下的业务源码
        TestExecutionResult mainCompileError = result("COMPILE_ERROR", false, 1, 0,
                "[ERROR] /x/src/main/java/com/example/Calculator.java:[5,8] ';' expected");
        when(testExecutionService.executeTests(anyString())).thenReturn(mainCompileError);

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        assertFalse(r.isOptimizationSuccess());
        assertEquals(0, r.getRetryCount());
        verify(llmService, never()).chat(anyString());
    }

    // 17. 并发优化锁：同一 projectId 同时只能有一个优化流程
    @Test
    void optimize_concurrentSameProject_rejected() throws Exception {
        setupCommonMocks();
        writeExistingTest(validJUnit4Test());
        // 让第一次执行阻塞，第二次被拒绝
        when(testExecutionService.executeTests(anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(300);
                    return result("PASSED", true, 0, 0, "");
                });

        Thread t1 = new Thread(() -> optimizationService.optimize("pid", buildRequest()));
        t1.start();
        Thread.sleep(100);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> optimizationService.optimize("pid", buildRequest()));
        assertEquals(409, ex.getCode());

        t1.join();
    }

    // 18. validateRepairedCode：修复后删除测试方法应被拒绝
    @Test
    void validateRepairedCode_removesTestMethod_rejected() {
        String original = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    Assert.assertEquals(3, new Calculator().add(1, 2));\n"
                + "  }\n"
                + "  @Test\n"
                + "  public void testSub() {\n"
                + "    Assert.assertEquals(1, new Calculator().sub(3, 2));\n"
                + "  }\n"
                + "}\n";
        String repaired = validJUnit4Test(); // 少了一个 @Test 方法

        String error = PromptBuilder.validateRepairedCode(
                original, repaired, "Calculator", "com.example", JUnitVersion.JUNIT4);

        assertNotNull(error);
        assertTrue(error.contains("测试方法") || error.contains("减少") || error.contains("@Test"));
    }

    // 19. validateRepairedCode：添加 @Disabled 跳过注解应被拒绝
    @Test
    void validateRepairedCode_addsDisabled_rejected() {
        String original = validJUnit4Test();
        String repaired = validJUnit4Test().replace("@Test", "@Disabled\n  @Test");

        String error = PromptBuilder.validateRepairedCode(
                original, repaired, "Calculator", "com.example", JUnitVersion.JUNIT4);

        assertNotNull(error);
        assertTrue(error.contains("Disabled") || error.contains("跳过") || error.contains("Ignore"));
    }

    // 20. validateRepairedCode：添加 @Ignore 跳过注解应被拒绝
    @Test
    void validateRepairedCode_addsIgnore_rejected() {
        String original = validJUnit4Test();
        String repaired = validJUnit4Test().replace("@Test", "@Ignore\n  @Test");

        String error = PromptBuilder.validateRepairedCode(
                original, repaired, "Calculator", "com.example", JUnitVersion.JUNIT4);

        assertNotNull(error);
        assertTrue(error.contains("Disabled") || error.contains("跳过") || error.contains("Ignore"));
    }

    // 21. 修复后编译错误来自业务源码（非 TEST_SOURCE）：恢复测试代码并停止
    @Test
    void optimize_repairedThenMainSourceCompileError_restoresAndStops() throws IOException {
        setupCommonMocks();
        Path testFile = writeExistingTest(validJUnit4Test());
        String original = validJUnit4Test();

        // 初次 FAILED -> 修复 -> 再次执行 COMPILE_ERROR（错误来自 src/main/java）
        when(testExecutionService.executeTests(anyString()))
                .thenReturn(result("FAILED", false, 1, 1, ""))
                .thenReturn(result("COMPILE_ERROR", false, 1, 0,
                        "[ERROR] /x/src/main/java/com/example/Calculator.java:[5,8] ';' expected"));
        when(llmService.chat(anyString())).thenReturn("```java\n" + validJUnit4Test() + "```");

        TestOptimizationResult r = optimizationService.optimize("pid", buildRequest());

        assertFalse(r.isOptimizationSuccess());
        assertEquals(1, r.getRetryCount());
        // 只调用一次 LLM（修复后不再继续）
        verify(llmService, times(1)).chat(anyString());
        // 测试代码应被恢复为原始版本
        assertEquals(original, Files.readString(testFile, StandardCharsets.UTF_8));
        // 修复记录中应标记为已恢复（repaired=true 表示代码已恢复）
        RepairRecord rec = r.getRepairRecords().get(0);
        assertTrue(rec.isRepaired(), "应记录测试代码已恢复");
        assertTrue(rec.getFeedbackSummary().contains("恢复"));
    }

    // 22. validateRepairedCode：删除断言应被拒绝
    @Test
    void validateRepairedCode_removesAssertions_rejected() {
        String original = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    Calculator c = new Calculator();\n"
                + "    Assert.assertEquals(3, c.add(1, 2));\n"
                + "    Assert.assertTrue(c.add(1, 2) > 0);\n"
                + "  }\n"
                + "}\n";
        // 修复后删除了一个断言（只剩一个 assertEquals）
        String repaired = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    Calculator c = new Calculator();\n"
                + "    Assert.assertEquals(3, c.add(1, 2));\n"
                + "  }\n"
                + "}\n";

        String error = PromptBuilder.validateRepairedCode(
                original, repaired, "Calculator", "com.example", JUnitVersion.JUNIT4);

        assertNotNull(error);
        assertTrue(error.contains("断言"), "错误信息应提到断言数量: " + error);
    }

    // 23. validateRepairedCode：相同数量断言和测试方法应通过
    @Test
    void validateRepairedCode_sameCount_passes() {
        String original = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    Assert.assertEquals(3, new Calculator().add(1, 2));\n"
                + "  }\n"
                + "}\n";
        String repaired = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    Assert.assertEquals(3, new Calculator().add(1, 2));\n"
                + "  }\n"
                + "}\n";

        String error = PromptBuilder.validateRepairedCode(
                original, repaired, "Calculator", "com.example", JUnitVersion.JUNIT4);

        assertNull(error, "相同数量的测试方法和断言应通过校验");
    }

    // 24. containsSkipAnnotation：注释中的 @Disabled 不应被误判
    @Test
    void containsSkipAnnotation_disabledInComment_notDetected() {
        String code = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "// 注意：这里提到了 @Disabled 但它只是注释\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    Assert.assertEquals(3, new Calculator().add(1, 2));\n"
                + "  }\n"
                + "}\n";

        assertFalse(PromptBuilder.containsSkipAnnotation(code),
                "注释中的 @Disabled 不应被判定为跳过注解");
    }

    // 25. containsSkipAnnotation：字符串中的 @Ignore 不应被误判
    @Test
    void containsSkipAnnotation_ignoreInString_notDetected() {
        String code = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    String note = \"此测试不能被 @Ignore\";\n"
                + "    Assert.assertEquals(3, new Calculator().add(1, 2));\n"
                + "  }\n"
                + "}\n";

        assertFalse(PromptBuilder.containsSkipAnnotation(code),
                "字符串字面量中的 @Ignore 不应被判定为跳过注解");
    }

    // 26. countAssertions：自定义 assertSomething() 不应被当成 JUnit 断言
    @Test
    void countAssertions_customAssertMethod_notCounted() {
        String code = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "public class CalculatorTest {\n"
                + "  private void assertSomething(Object o) {}\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    assertSomething(new Object());\n"
                + "    assertSomething(null);\n"
                + "  }\n"
                + "}\n";

        assertEquals(0, PromptBuilder.countAssertions(code),
                "自定义的 assertSomething() 不应被统计为 JUnit 断言");
    }

    // 27. countAssertions：真实 JUnit 断言应被正确统计
    @Test
    void countAssertions_realJUnitAssertions_counted() {
        String code = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import org.junit.Assert;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    Assert.assertEquals(3, new Calculator().add(1, 2));\n"
                + "    Assert.assertTrue(new Calculator().add(1, 2) > 0);\n"
                + "  }\n"
                + "}\n";

        assertEquals(2, PromptBuilder.countAssertions(code),
                "真实的 Assert.assertXxx 应被统计为 JUnit 断言");
    }

    // 28. validateRepairedCode：JavaParser 解析失败时应拒绝修复代码
    @Test
    void validateRepairedCode_unparseableCode_rejected() {
        String original = validJUnit4Test();
        // 修复后的代码是不合法的 Java 语法（缺少 class 声明闭合）
        String repaired = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    Assert.assertEquals(3, new Calculator().add(1, 2));\n"
                + "  }\n";
        // 缺少闭合 }，JavaParser 解析失败

        String error = PromptBuilder.validateRepairedCode(
                original, repaired, "Calculator", "com.example", JUnitVersion.JUNIT4);

        assertNotNull(error, "JavaParser 解析失败的修复代码应被拒绝");
    }

    // 29. countAssertions：静态导入的 JUnit 断言应被正确统计
    @Test
    void countAssertions_staticallyImportedAssertions_counted() {
        String code = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "import static org.junit.Assert.assertEquals;\n"
                + "import static org.junit.Assert.assertTrue;\n"
                + "public class CalculatorTest {\n"
                + "  @Test\n"
                + "  public void testAdd() {\n"
                + "    assertEquals(3, new Calculator().add(1, 2));\n"
                + "    assertTrue(new Calculator().add(1, 2) > 0);\n"
                + "  }\n"
                + "}\n";

        assertEquals(2, PromptBuilder.countAssertions(code),
                "静态导入的 Assert.assertXxx 应被统计为 JUnit 断言");
    }
}
