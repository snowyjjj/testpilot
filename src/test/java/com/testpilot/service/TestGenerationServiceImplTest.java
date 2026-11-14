package com.testpilot.service;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.MethodInfo;
import com.testpilot.dto.MethodParameter;
import com.testpilot.dto.TestGenerationRequest;
import com.testpilot.dto.TestGenerationResponse;
import com.testpilot.service.impl.TestGenerationServiceImpl;
import com.testpilot.utils.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGenerationServiceImplTest {

    private TestGenerationServiceImpl testGenerationService;
    private ProjectService projectService;
    private LLMService llmService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        llmService = mock(LLMService.class);
        testGenerationService = new TestGenerationServiceImpl(projectService, llmService);
    }

    private MethodContext buildContext(String sourceFilePath) {
        MethodParameter p = new MethodParameter("a", "int");
        MethodParameter p2 = new MethodParameter("b", "int");
        MethodInfo methodInfo = new MethodInfo("add", "int", "public",
                false, List.of(p, p2), "public int add(int a, int b) { return a + b; }");
        String classSource = "package com.example;\npublic class Calculator {\n"
                + "  public int add(int a, int b) { return a + b; }\n}";
        return new MethodContext("com.example", "Calculator", sourceFilePath, classSource, methodInfo);
    }

    private TestGenerationRequest buildRequest() {
        TestGenerationRequest req = new TestGenerationRequest();
        req.setClassName("Calculator");
        req.setMethodName("add");
        req.setParamTypes(List.of("int", "int"));
        return req;
    }

    // ============ PromptBuilder 测试 ============

    @Test
    void prompt_containsMethodSource() {
        MethodContext ctx = buildContext("src/main/java/com/example/Calculator.java");
        String prompt = PromptBuilder.buildTestPrompt(ctx);
        assertTrue(prompt.contains("public int add(int a, int b) { return a + b; }"));
        assertTrue(prompt.contains("com.example"));
        assertTrue(prompt.contains("JUnit 5"));
    }

    @Test
    void extractJavaCode_javaFence_extractsContent() {
        String response = "Here is the code:\n```java\npackage com.example;\nclass CalculatorTest {}\n```\nDone.";
        String code = PromptBuilder.extractJavaCode(response);
        assertTrue(code.contains("package com.example;"));
        assertTrue(code.contains("class CalculatorTest"));
        assertFalse(code.contains("```"));
    }

    @Test
    void extractJavaCode_plainFence_extractsContent() {
        String response = "```\npackage com.example;\nclass X {}\n```";
        String code = PromptBuilder.extractJavaCode(response);
        assertTrue(code.contains("class X"));
    }

    @Test
    void extractJavaCode_noFence_returnsFullText() {
        String response = "package com.example;\nclass X {}";
        assertEquals(response, PromptBuilder.extractJavaCode(response));
    }

    @Test
    void validateGeneratedCode_validCode_passes() {
        String code = "package com.example;\nimport org.junit.jupiter.api.Test;\nclass CalculatorTest {\n  @Test void t() {}\n}";
        assertNull(PromptBuilder.validateGeneratedCode(code, "com.example"));
    }

    @Test
    void validateGeneratedCode_missingTestAnnotation_fails() {
        String code = "package com.example;\nclass CalculatorTest {}";
        String err = PromptBuilder.validateGeneratedCode(code, "com.example");
        assertNotNull(err);
        assertTrue(err.contains("@Test"));
    }

    @Test
    void validateGeneratedCode_wrongPackage_fails() {
        String code = "package wrong;\nclass X { @Test void t(){} }";
        String err = PromptBuilder.validateGeneratedCode(code, "com.example");
        assertNotNull(err);
        assertTrue(err.contains("package"));
    }

    // ============ TestGenerationServiceImpl 测试 ============

    @Test
    void generateTest_success_savesFile() throws Exception {
        MethodContext ctx = buildContext("src/main/java/com/example/Calculator.java");
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(ctx);
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);

        String generated = "```java\npackage com.example;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "class CalculatorTest {\n  @Test void shouldAdd() {}\n}\n```";
        when(llmService.chat(anyString())).thenReturn(generated);

        TestGenerationResponse resp = testGenerationService.generateTest("pid-1", buildRequest());

        assertEquals("GENERATED", resp.getStatus());
        assertEquals("src/test/java/com/example/CalculatorTest.java", resp.getTestFilePath());
        assertTrue(resp.getGeneratedCode().contains("class CalculatorTest"));

        Path saved = tempDir.resolve("src/test/java/com/example/CalculatorTest.java");
        assertTrue(Files.exists(saved));
        assertTrue(Files.readString(saved).contains("@Test"));
    }

    @Test
    void generateTest_invalidModelResponse_doesNotSave() {
        MethodContext ctx = buildContext("src/main/java/com/example/Calculator.java");
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(ctx);
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);
        // 模型返回无 @Test 的内容
        when(llmService.chat(anyString())).thenReturn("```java\npackage com.example;\nclass X {}\n```");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> testGenerationService.generateTest("pid-1", buildRequest()));
        assertTrue(ex.getMessage().contains("@Test"));

        // 不应保存任何文件
        Path saved = tempDir.resolve("src/test/java/com/example/CalculatorTest.java");
        assertFalse(Files.exists(saved));
    }

    @Test
    void generateTest_fileExists_rejectsOverwrite() throws Exception {
        MethodContext ctx = buildContext("src/main/java/com/example/Calculator.java");
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(ctx);
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);

        // 预先创建测试文件
        Path existing = tempDir.resolve("src/test/java/com/example/CalculatorTest.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "old content");

        when(llmService.chat(anyString())).thenReturn("```java\npackage com.example;\n"
                + "import org.junit.jupiter.api.Test;\nclass CalculatorTest { @Test void t(){} }\n```");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> testGenerationService.generateTest("pid-1", buildRequest()));
        assertEquals(409, ex.getCode());

        // 原文件内容不变
        assertEquals("old content", Files.readString(existing));
    }

    @Test
    void generateTest_subdirectoryStructure_correctPath() throws Exception {
        // ZIP 内含单层子目录 myapp
        MethodContext ctx = buildContext("myapp/src/main/java/com/example/Calculator.java");
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(ctx);
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);

        when(llmService.chat(anyString())).thenReturn("```java\npackage com.example;\n"
                + "import org.junit.jupiter.api.Test;\nclass CalculatorTest { @Test void t(){} }\n```");

        TestGenerationResponse resp = testGenerationService.generateTest("pid-1", buildRequest());

        assertEquals("myapp/src/test/java/com/example/CalculatorTest.java", resp.getTestFilePath());
        assertTrue(Files.exists(tempDir.resolve("myapp/src/test/java/com/example/CalculatorTest.java")));
    }
}
