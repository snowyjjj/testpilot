package com.testpilot.service;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.JUnitVersion;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.MethodInfo;
import com.testpilot.dto.MethodParameter;
import com.testpilot.dto.TestGenerationRequest;
import com.testpilot.dto.TestGenerationResponse;
import com.testpilot.service.impl.TestGenerationServiceImpl;
import com.testpilot.utils.JUnitDetector;
import com.testpilot.utils.ProjectExecutionLock;
import com.testpilot.utils.PromptBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGenerationServiceImplTest {

    private TestGenerationServiceImpl testGenerationService;
    private ProjectService projectService;
    private LLMService llmService;
    private ProjectExecutionLock projectExecutionLock;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        projectService = mock(ProjectService.class);
        llmService = mock(LLMService.class);
        projectExecutionLock = new ProjectExecutionLock();
        testGenerationService = new TestGenerationServiceImpl(projectService, llmService, projectExecutionLock);
        // 默认写入 JUnit 4 的 pom.xml
        writePom(tempDir, JUNIT4_DEP);
    }

    private static final String JUNIT4_DEP =
            "    <dependency>\n      <groupId>junit</groupId>\n      <artifactId>junit</artifactId>\n"
                    + "      <version>4.13.2</version>\n      <scope>test</scope>\n    </dependency>\n";

    private static final String JUNIT5_DEP =
            "    <dependency>\n      <groupId>org.junit.jupiter</groupId>\n      <artifactId>junit-jupiter</artifactId>\n"
                    + "      <version>5.10.0</version>\n      <scope>test</scope>\n    </dependency>\n";

    private void writePom(Path dir, String dependencyXml) throws IOException {
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n  <artifactId>demo</artifactId>\n  <version>1.0</version>\n"
                + "  <dependencies>\n" + dependencyXml + "  </dependencies>\n"
                + "</project>\n";
        Files.writeString(dir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
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
        String prompt = PromptBuilder.buildTestPrompt(ctx, JUnitVersion.JUNIT5);
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
                + "import org.junit.Test;\nimport static org.junit.Assert.*;\n"
                + "public class CalculatorTest {\n  @Test public void shouldAdd() {}\n}\n```";
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
                + "import org.junit.Test;\npublic class CalculatorTest { @Test public void t(){} }\n```");

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
                + "import org.junit.Test;\npublic class CalculatorTest { @Test public void t(){} }\n```");

        TestGenerationResponse resp = testGenerationService.generateTest("pid-1", buildRequest());

        assertEquals("myapp/src/test/java/com/example/CalculatorTest.java", resp.getTestFilePath());
        assertTrue(Files.exists(tempDir.resolve("myapp/src/test/java/com/example/CalculatorTest.java")));
    }

    // ============ JUnit 版本检测测试 ============

    @Test
    void junitDetector_detectsJUnit4() {
        assertEquals(JUnitVersion.JUNIT4, JUnitDetector.detect(tempDir));
    }

    @Test
    void junitDetector_detectsJUnit5() throws IOException {
        writePom(tempDir, JUNIT5_DEP);
        assertEquals(JUnitVersion.JUNIT5, JUnitDetector.detect(tempDir));
    }

    @Test
    void junitDetector_noPom_returnsUnknown() throws IOException {
        Files.delete(tempDir.resolve("pom.xml"));
        assertEquals(JUnitVersion.UNKNOWN, JUnitDetector.detect(tempDir));
    }

    @Test
    void junitDetector_noJUnitDep_returnsUnknown() throws IOException {
        writePom(tempDir, "    <dependency>\n      <groupId>com.google.guava</groupId>\n"
                + "      <artifactId>guava</artifactId>\n      <version>32.0.0-jre</version>\n    </dependency>\n");
        assertEquals(JUnitVersion.UNKNOWN, JUnitDetector.detect(tempDir));
    }

    @Test
    void junitDetector_springBootParentOnly_returnsUnknown() throws IOException {
        // 仅有 Spring Boot Parent，没有任何测试依赖，不能仅凭 Parent 判定 JUnit 5
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent>\n    <groupId>org.springframework.boot</groupId>\n"
                + "    <artifactId>spring-boot-starter-parent</artifactId>\n    <version>2.7.0</version>\n  </parent>\n"
                + "  <groupId>com.example</groupId>\n  <artifactId>demo</artifactId>\n  <version>1.0</version>\n"
                + "</project>\n";
        Files.writeString(tempDir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
        assertEquals(JUnitVersion.UNKNOWN, JUnitDetector.detect(tempDir));
    }

    @Test
    void junitDetector_springBootStarterTestWithParent_returnsJUnit5() throws IOException {
        // 实际声明 spring-boot-starter-test，版本由 spring-boot-starter-parent 继承
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent>\n    <groupId>org.springframework.boot</groupId>\n"
                + "    <artifactId>spring-boot-starter-parent</artifactId>\n    <version>2.7.0</version>\n  </parent>\n"
                + "  <groupId>com.example</groupId>\n  <artifactId>demo</artifactId>\n  <version>1.0</version>\n"
                + "  <dependencies>\n    <dependency>\n      <groupId>org.springframework.boot</groupId>\n"
                + "      <artifactId>spring-boot-starter-test</artifactId>\n      <scope>test</scope>\n    </dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        Files.writeString(tempDir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
        assertEquals(JUnitVersion.JUNIT5, JUnitDetector.detect(tempDir));
    }

    @Test
    void junitDetector_dependencyManagementOnlyJUnit5_returnsUnknown() throws IOException {
        // 仅在 dependencyManagement 中声明 junit-bom，无实际 junit 依赖 → 不能判定为 JUnit 5
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n  <artifactId>demo</artifactId>\n  <version>1.0</version>\n"
                + "  <dependencyManagement>\n    <dependencies>\n      <dependency>\n"
                + "        <groupId>org.junit</groupId>\n        <artifactId>junit-bom</artifactId>\n"
                + "        <version>5.10.0</version>\n        <type>pom</type>\n        <scope>import</scope>\n"
                + "      </dependency>\n    </dependencies>\n  </dependencyManagement>\n"
                + "  <dependencies>\n    <dependency>\n      <groupId>com.google.guava</groupId>\n"
                + "      <artifactId>guava</artifactId>\n      <version>32.0.0-jre</version>\n    </dependency>\n  </dependencies>\n"
                + "</project>\n";
        Files.writeString(tempDir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
        assertEquals(JUnitVersion.UNKNOWN, JUnitDetector.detect(tempDir));
    }

    @Test
    void junitDetector_dependencyManagementJUnit5WithJUnit4Actual_returnsJUnit4() throws IOException {
        // dependencyManagement 管理 JUnit 5 BOM，但实际依赖声明的是 JUnit 4 → 以实际依赖为准
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n  <artifactId>demo</artifactId>\n  <version>1.0</version>\n"
                + "  <dependencyManagement>\n    <dependencies>\n      <dependency>\n"
                + "        <groupId>org.junit</groupId>\n        <artifactId>junit-bom</artifactId>\n"
                + "        <version>5.10.0</version>\n        <type>pom</type>\n        <scope>import</scope>\n"
                + "      </dependency>\n    </dependencies>\n  </dependencyManagement>\n"
                + "  <dependencies>\n    <dependency>\n      <groupId>junit</groupId>\n"
                + "      <artifactId>junit</artifactId>\n      <version>4.13.2</version>\n      <scope>test</scope>\n    </dependency>\n  </dependencies>\n"
                + "</project>\n";
        Files.writeString(tempDir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
        assertEquals(JUnitVersion.JUNIT4, JUnitDetector.detect(tempDir));
    }

    @Test
    void junitDetector_bothJUnit4AndJUnit5_returnsConflict() throws IOException {
        // 同时实际声明 JUnit 4 和 JUnit 5 → 冲突
        String both = JUNIT4_DEP + JUNIT5_DEP;
        writePom(tempDir, both);
        assertEquals(JUnitVersion.CONFLICT, JUnitDetector.detect(tempDir));
    }

    @Test
    void junitDetector_maliciousXxeXml_returnsUnknownSafely() throws IOException {
        // 恶意 XXE：DOCTYPE + 外部实体，解析器应拒绝加载，不崩溃，返回 UNKNOWN
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>&xxe;</groupId>\n  <artifactId>demo</artifactId>\n  <version>1.0</version>\n"
                + "</project>\n";
        Files.writeString(tempDir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
        assertEquals(JUnitVersion.UNKNOWN, JUnitDetector.detect(tempDir));
    }

    @Test
    void generateTest_unknownJUnitVersion_throwsException() throws IOException {
        // 移除 pom 中的 JUnit 依赖
        writePom(tempDir, "    <dependency>\n      <groupId>com.google.guava</groupId>\n"
                + "      <artifactId>guava</artifactId>\n      <version>32.0.0-jre</version>\n    </dependency>\n");
        MethodContext ctx = buildContext("src/main/java/com/example/Calculator.java");
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(ctx);
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> testGenerationService.generateTest("pid-1", buildRequest()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("JUnit"));
    }

    // ============ Prompt 版本适配测试 ============

    @Test
    void prompt_junit4_doesNotContainJunit5Api() {
        MethodContext ctx = buildContext("src/main/java/com/example/Calculator.java");
        String prompt = PromptBuilder.buildTestPrompt(ctx, JUnitVersion.JUNIT4);
        assertTrue(prompt.contains("JUnit 4"));
        assertTrue(prompt.contains("org.junit.Test"));
        assertTrue(prompt.contains("org.junit.Assert"));
        assertFalse(prompt.contains("org.junit.jupiter.api.Test"));
        // JUnit 4 prompt 明确禁止使用 @DisplayName 和 assertThrows
        assertTrue(prompt.contains("不要使用 @DisplayName"));
        assertTrue(prompt.contains("assertThrows"));
        assertTrue(prompt.contains("JUnit 5 专属 API"));
    }

    @Test
    void prompt_junit5_containsCorrectApi() {
        MethodContext ctx = buildContext("src/main/java/com/example/Calculator.java");
        String prompt = PromptBuilder.buildTestPrompt(ctx, JUnitVersion.JUNIT5);
        assertTrue(prompt.contains("JUnit 5"));
        assertTrue(prompt.contains("org.junit.jupiter.api.Test"));
        assertTrue(prompt.contains("org.junit.jupiter.api.Assertions"));
        assertTrue(prompt.contains("@DisplayName"));
        assertTrue(prompt.contains("assertThrows"));
    }

    @Test
    void validateGeneratedCode_junit4_rejectsJunit5Import() {
        // 先通过 import org.junit.Test 检查，再被 junit.jupiter 拦截
        String code = "package com.example;\nimport org.junit.Test;\nimport org.junit.jupiter.api.Assertions;\n"
                + "public class X { @Test public void t(){} }";
        String err = PromptBuilder.validateGeneratedCode(code, "com.example", JUnitVersion.JUNIT4);
        assertNotNull(err);
        assertTrue(err.contains("junit.jupiter"));
    }

    @Test
    void validateGeneratedCode_junit4_requiresOrgJunitTestImport() {
        String code = "package com.example;\npublic class X { @Test public void t(){} }";
        String err = PromptBuilder.validateGeneratedCode(code, "com.example", JUnitVersion.JUNIT4);
        assertNotNull(err);
        assertTrue(err.contains("org.junit.Test"));
    }

    @Test
    void validateGeneratedCode_junit5_requiresJupiterImport() {
        String code = "package com.example;\nimport org.junit.Test;\npublic class X { @Test public void t(){} }";
        String err = PromptBuilder.validateGeneratedCode(code, "com.example", JUnitVersion.JUNIT5);
        assertNotNull(err);
        assertTrue(err.contains("jupiter"));
    }

    // ============ 6.2 修复：测试生成写入受 ProjectExecutionLock 保护 ============

    /** 由另一个线程验证锁已释放（ReentrantLock 同线程重入会掩盖检查，必须用独立线程）。 */
    private void assertLockFreeFromAnotherThread(String projectId) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> acquired = new AtomicReference<>(false);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread checker = new Thread(() -> {
            try {
                boolean ok = projectExecutionLock.tryLock(projectId);
                acquired.set(ok);
                if (ok) {
                    projectExecutionLock.unlock(projectId);
                }
            } catch (Throwable e) {
                err.set(e);
            } finally {
                done.countDown();
            }
        });
        checker.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "checker 线程应在 5s 内完成");
        assertNull(err.get(), "checker 线程不应抛出异常");
        assertTrue(acquired.get(), "锁应已释放，另一线程应能成功获取");
    }

    private void stubNormalGeneration() {
        MethodContext ctx = buildContext("src/main/java/com/example/Calculator.java");
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(ctx);
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);
        when(llmService.chat(anyString())).thenReturn("```java\npackage com.example;\n"
                + "import org.junit.Test;\npublic class CalculatorTest { @Test public void t(){} }\n```");
    }

    @Test
    void generateTest_success_releasesLockAfterWrite() throws Exception {
        stubNormalGeneration();

        TestGenerationResponse resp = testGenerationService.generateTest("pid-lock", buildRequest());

        assertEquals("GENERATED", resp.getStatus());
        assertTrue(Files.exists(tempDir.resolve("src/test/java/com/example/CalculatorTest.java")));
        // 写入完成后锁必须释放（由另一线程验证，避免同线程重入掩盖）
        assertLockFreeFromAnotherThread("pid-lock");
    }

    @Test
    void generateTest_lockHeldByOtherThread_returns409() throws Exception {
        stubNormalGeneration();

        // 由另一线程持有锁，模拟同项目正在执行其他操作
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                assertTrue(projectExecutionLock.tryLock("pid-lock"));
                acquired.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (Throwable e) {
                err.set(e);
            } finally {
                projectExecutionLock.unlock("pid-lock");
            }
        });
        holder.start();
        assertTrue(acquired.await(5, TimeUnit.SECONDS), "持有线程应先获取到锁");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> testGenerationService.generateTest("pid-lock", buildRequest()));
        assertEquals(409, ex.getCode());

        // 锁被拦截时不应写入任何文件
        assertFalse(Files.exists(tempDir.resolve("src/test/java/com/example/CalculatorTest.java")));

        release.countDown();
        holder.join(5000);
        assertNull(err.get(), "持有线程不应抛出异常");
    }

    @Test
    void generateTest_writeException_releasesLock() throws Exception {
        stubNormalGeneration();
        // 构造：目标父路径 com/example 预先存在为普通文件，使 saveTestFile 的 createDirectories 抛出，
        // 触发写入异常路径，验证 finally 中锁仍被释放。
        Files.createDirectories(tempDir.resolve("src/test/java/com"));
        Files.writeString(tempDir.resolve("src/test/java/com/example"), "not a dir");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> testGenerationService.generateTest("pid-lock", buildRequest()));
        // saveTestFile 将 IOException 转为 500 业务错误
        assertEquals(500, ex.getCode());

        // 异常情况下锁不能遗留
        assertLockFreeFromAnotherThread("pid-lock");
    }

    // ============ P1 修复：测试生成写入前路径边界校验 ============

    /** 统计 projectDir 下匹配指定前缀、以 .tmp 结尾的普通文件数量，用于验证无临时文件残留。 */
    private long countTempFiles(Path dir, String prefix) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(prefix) && n.endsWith(".tmp"))
                    .count();
        }
    }

    @Test
    void generateTest_success_noTempFileLeak() throws Exception {
        stubNormalGeneration();

        TestGenerationResponse resp = testGenerationService.generateTest("pid-p1", buildRequest());

        assertEquals("GENERATED", resp.getStatus());
        Path target = tempDir.resolve("src/test/java/com/example/CalculatorTest.java");
        assertTrue(Files.exists(target), "测试文件应正常写入项目目录内");
        // 成功写入后不应残留任何 .test-*.tmp 临时文件；临时文件只在已校验的父目录下创建
        assertEquals(0, countTempFiles(tempDir, ".test-"), "不应残留临时文件");
    }

    @Test
    void generateTest_pathOutsideProject_rejected() {
        // sourceFilePath 含足够多的 .. 使归一化后的路径越出项目目录
        MethodContext ctx = buildContext("src/main/java/../../../../Evil.java");
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(ctx);
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> testGenerationService.generateTest("pid-p1", buildRequest()));
        assertNotNull(ex.getMessage());

        // 项目目录之外不得产生任何文件
        Path escaped = tempDir.getParent().resolve("Evil.java");
        assertFalse(Files.exists(escaped), "越界路径不得写入项目目录之外");
    }

    @Test
    void generateTest_dotDotTraversal_rejected() {
        // sourceFilePath 以 .. 开头，直接从项目根向上穿越
        MethodContext ctx = buildContext("../../Evil.java");
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(ctx);
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);

        assertThrows(RuntimeException.class,
                () -> testGenerationService.generateTest("pid-p1", buildRequest()));

        Path escaped = tempDir.resolve("../../Evil.java").normalize();
        assertFalse(Files.exists(escaped), "含 .. 的穿越路径不得写入");
    }

    @Test
    void generateTest_symlinkEscape_rejected() throws Exception {
        // 在项目目录内创建一个指向项目外目录的符号链接目录。
        // resolveTestFilePath 的字符串前缀校验会通过（链接名位于项目内），
        // 但 saveTestFile 调用的 assertPathWithinProject 会逐级检测符号链接链并拒绝写入，
        // 从而验证写入边界的纵深防御真正生效。
        Path realOutsideDir = tempDir.resolve("outside");
        Files.createDirectories(realOutsideDir);

        Path symlinkDir = tempDir.resolve("evil");
        boolean symlinkSupported;
        try {
            Files.createSymbolicLink(symlinkDir, realOutsideDir);
            symlinkSupported = Files.isSymbolicLink(symlinkDir);
        } catch (IOException | UnsupportedOperationException e) {
            symlinkSupported = false;
        }
        // Windows 通常需要管理员权限或开发者模式才能创建符号链接；无法可靠创建时跳过，不伪造通过
        Assumptions.assumeTrue(symlinkSupported,
                "当前环境无法可靠创建/检测符号链接，跳过符号链接逃逸测试");

        // sourceFilePath 不含 src/main/java → 走 else 分支，testRelative = "evil/CalculatorTest.java"
        MethodContext ctx = buildContext("evil/Calculator.java");
        when(projectService.getMethodContext(anyString(), anyString(), anyString(), any()))
                .thenReturn(ctx);
        when(projectService.getProjectDir(anyString())).thenReturn(tempDir);
        when(llmService.chat(anyString())).thenReturn("```java\npackage com.example;\n"
                + "import org.junit.Test;\npublic class CalculatorTest { @Test public void t(){} }\n```");

        Exception ex = assertThrows(Exception.class,
                () -> testGenerationService.generateTest("pid-p1", buildRequest()));
        assertNotNull(ex.getMessage());

        // 符号链接指向的真实外部目录不得被写入
        Path escapedFile = realOutsideDir.resolve("CalculatorTest.java");
        assertFalse(Files.exists(escapedFile), "符号链接逃逸不得写入项目目录之外");
        // 也不得残留临时文件
        assertEquals(0, countTempFiles(tempDir, ".test-"), "被拒绝后不应残留临时文件");
    }
}
