package com.testpilot.service;

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
        optimizationService = new TestOptimizationServiceImpl(projectService, testExecutionService, llmService);

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
                "[ERROR] /x/CalculatorTest.java:[10,13] cannot find symbol\n[ERROR] symbol: class Foo");
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
                        "[ERROR] /x/CalculatorTest.java:[10,13] cannot find symbol"));
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
                        "[ERROR] /x/CalculatorTest.java:[10,13] cannot find symbol"))
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
                        "[ERROR] /x/CalculatorTest.java:[10,13] cannot find symbol"))
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
}
