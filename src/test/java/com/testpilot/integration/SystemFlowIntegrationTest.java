package com.testpilot.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.dto.TestGenerationRequest;
import com.testpilot.dto.TestOptimizationRequest;
import com.testpilot.service.LLMService;
import com.testpilot.service.ProjectService;
import com.testpilot.service.TestExecutionService;
import com.testpilot.service.TestOptimizationService;
import com.testpilot.utils.MavenProcessRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段8 用例 A：系统正常业务链路集成测试。
 * <p>
 * 使用 {@code @SpringBootTest + @AutoConfigureMockMvc} 启动完整 Spring 容器，
 * 通过真实 HTTP 接口验证：上传 → 解析 → 生成 → 执行 → 覆盖率 → 质量评估 全链路。
 * <p>
 * 仅 Mock 两个外部依赖：{@link LLMService}（避免调用真实百炼 API）、
 * {@link MavenProcessRunner}（避免启动真实 Maven 子进程）。
 * 其余组件（Controller / Service / ProjectServiceImpl / ProjectExecutionLock /
 * SurefireReportParser / JacocoReportParser / TestQualityAnalyzer / QualityScorer /
 * PromptBuilder / TestFileManager / GlobalExceptionHandler / 真实文件系统）全部真实运行。
 * <p>
 * 存储隔离：通过 {@code @DynamicPropertySource} 将
 * {@code testpilot.storage.project-path} 与 {@code testpilot.storage.extracted-path}
 * 重定向到 JUnit 生命周期外显式创建的临时目录，避免污染项目默认 {@code storage/} 目录。
 * 这里不使用静态 {@code @TempDir}，改用静态初始化块显式创建目录，
 * 以保证 {@code @DynamicPropertySource} 执行时目录必然已就绪，不依赖扩展初始化顺序。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SystemFlowIntegrationTest {

    /**
     * 临时存储根目录。在类加载时（任何 JUnit / Spring 回调之前）显式创建，
     * 生命周期清晰：{@code @DynamicPropertySource} 必然在其后执行。
     */
    private static final Path TEMP_STORAGE_ROOT;

    static {
        try {
            TEMP_STORAGE_ROOT = Files.createTempDirectory("testpilot-it-storage-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        Path projects = TEMP_STORAGE_ROOT.resolve("projects").toAbsolutePath().normalize();
        Path extracted = TEMP_STORAGE_ROOT.resolve("extracted").toAbsolutePath().normalize();
        registry.add("testpilot.storage.project-path", projects::toString);
        registry.add("testpilot.storage.extracted-path", extracted::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @MockBean
    private LLMService llmService;

    @MockBean
    private MavenProcessRunner mavenProcessRunner;

    @Autowired
    private ObjectMapper objectMapper;

    /** 真实 TestExecutionServiceImpl（未被 Mock），用于结构守护断言。 */
    @Autowired
    private TestExecutionService testExecutionService;

    /** 真实 TestOptimizationServiceImpl（未被 Mock），用于结构守护断言。 */
    @Autowired
    private TestOptimizationService testOptimizationService;

    // ============================== 第一步：基础设施验证 ==============================

    /**
     * 验证：
     * 1. 完整 Spring ApplicationContext 可以启动（无需 DASHSCOPE_API_KEY、不访问真实百炼 API）。
     * 2. Spring 注入的存储路径确实指向临时目录（而非默认 storage/）。
     * 3. 临时目录在属性注册时已经正确初始化。
     * 4. 上下文启动没有创建或污染项目默认 storage/ 目录。
     */
    @Test
    void contextStarts_withTempStorageRedirected_andNoDefaultStoragePollution() {
        // 上下文成功启动（能执行到这里即证明），MockMvc 与真实 Service 均已注入
        assertNotNull(mockMvc, "MockMvc 应已注入");
        assertNotNull(projectService, "ProjectService 应已注入");

        // 临时目录在属性注册时已存在
        assertTrue(Files.isDirectory(TEMP_STORAGE_ROOT),
                "临时存储根目录应已创建: " + TEMP_STORAGE_ROOT);

        // 读取真实注入到 ProjectServiceImpl 的存储路径字段，确认确实指向临时目录
        String projectPath = (String) ReflectionTestUtils.getField(projectService, "projectStoragePath");
        String extractedPath = (String) ReflectionTestUtils.getField(projectService, "extractedStoragePath");
        assertNotNull(projectPath, "projectStoragePath 应已注入");
        assertNotNull(extractedPath, "extractedStoragePath 应已注入");

        Path injectedProjects = Paths.get(projectPath).toAbsolutePath().normalize();
        Path injectedExtracted = Paths.get(extractedPath).toAbsolutePath().normalize();
        Path root = TEMP_STORAGE_ROOT.toAbsolutePath().normalize();

        assertTrue(injectedProjects.startsWith(root),
                "project-path 必须位于临时目录下，实际: " + injectedProjects);
        assertTrue(injectedExtracted.startsWith(root),
                "extracted-path 必须位于临时目录下，实际: " + injectedExtracted);

        // 默认 storage 目录不在临时目录下，且本测试不会向其写入（后续用例亦如此）
        Path defaultProjects = Paths.get("storage", "projects").toAbsolutePath().normalize();
        Path defaultExtracted = Paths.get("storage", "extracted").toAbsolutePath().normalize();
        assertFalse(defaultProjects.startsWith(root),
                "默认 storage/projects 不应落在临时目录内");
        assertFalse(defaultExtracted.startsWith(root),
                "默认 storage/extracted 不应落在临时目录内");
    }

    // ============================== 用例 A：完整业务链路 ==============================

    /**
     * 用例 A：系统正常业务链路端到端验证。
     * 通过 MockMvc 依次调用真实 HTTP 接口：
     * A1 上传 → A2 源码解析 → A3 LLM 生成测试 → A4 测试执行 → A5 JaCoCo 覆盖率 → A6 质量评估。
     * <p>
     * 仅 LLMService 与 MavenProcessRunner 被 Mock；报告解析、覆盖率解析、质量评分、
     * 文件系统读写均为真实组件，所有断言均基于真实接口返回与真实落盘文件。
     */
    @Test
    void fullBusinessFlow_upload_parse_generate_execute_coverage_quality() throws Exception {
        // ------------------------------ A1：上传项目 ------------------------------
        byte[] zipBytes = IntegrationTestFixtures.buildProjectZip();
        MockMultipartFile file = new MockMultipartFile(
                "file", "calculator.zip", "application/zip", zipBytes);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").exists())
                .andExpect(jsonPath("$.data.projectId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andReturn();
        String projectId = readJson(uploadResult).path("data").path("projectId").asText();
        assertNotNull(projectId);
        assertFalse(projectId.isBlank(), "projectId 应非空");

        // 断言上传的 ZIP 真实落盘到临时存储目录（而非默认 storage/）
        Path uploadedZip = tempProjectsDir().resolve(projectId + ".zip");
        assertTrue(Files.exists(uploadedZip), "上传的 ZIP 应存在于临时存储: " + uploadedZip);
        // 同时验证未污染默认 storage
        assertFalse(Files.exists(Paths.get("storage", "projects").resolve(projectId + ".zip")),
                "默认 storage/projects 不应出现本测试上传的 ZIP");

        // 解压后的项目根目录（根 pom 项目，mavenDir 即解压根）
        Path extractedProjectDir = tempExtractedDir().resolve(projectId);

        // ------------------------------ A2：源码解析 ------------------------------
        MvcResult parseResult = mockMvc.perform(post("/api/projects/{projectId}/parse", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode files = readJson(parseResult).path("data").path("files");
        assertTrue(files.isArray() && files.size() >= 1, "应解析到至少一个源文件");

        boolean foundCalculator = false;
        boolean foundAdd = false;
        String addReturnType = null;
        String addAccess = null;
        List<String> addParamTypes = new ArrayList<>();
        for (JsonNode f : files) {
            if (!"com.example".equals(f.path("packageName").asText())) {
                continue;
            }
            for (JsonNode cls : f.path("classes")) {
                if (!"Calculator".equals(cls.path("className").asText())) {
                    continue;
                }
                foundCalculator = true;
                for (JsonNode m : cls.path("methods")) {
                    if ("add".equals(m.path("name").asText())) {
                        foundAdd = true;
                        addReturnType = m.path("returnType").asText();
                        addAccess = m.path("accessModifier").asText();
                        for (JsonNode p : m.path("parameters")) {
                            addParamTypes.add(p.path("type").asText());
                        }
                    }
                }
            }
        }
        assertTrue(foundCalculator, "解析结果应包含 Calculator 类");
        assertTrue(foundAdd, "解析结果应包含 add 方法");
        assertEquals("int", addReturnType, "add 返回类型应为 int");
        assertEquals("public", addAccess, "add 访问修饰符应为 public");
        assertEquals(List.of("int", "int"), addParamTypes, "add 参数类型应为 [int, int]");

        // ------------------------------ A3：生成测试 ------------------------------
        when(llmService.chat(anyString()))
                .thenReturn(IntegrationTestFixtures.llmResponseWithCalculatorTest());

        TestGenerationRequest genReq = new TestGenerationRequest();
        genReq.setClassName("Calculator");
        genReq.setMethodName("add");
        genReq.setParamTypes(List.of("int", "int"));

        MvcResult genResult = mockMvc.perform(post("/api/projects/{projectId}/tests/generate", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("GENERATED"))
                .andExpect(jsonPath("$.data.testFilePath").value("src/test/java/com/example/CalculatorTest.java"))
                .andReturn();
        String testFilePath = readJson(genResult).path("data").path("testFilePath").asText();
        assertEquals("src/test/java/com/example/CalculatorTest.java", testFilePath,
                "测试文件相对路径应正确");

        // 不能只检查接口返回值，必须检查真实文件系统
        Path generatedFile = extractedProjectDir.resolve(testFilePath);
        assertTrue(Files.exists(generatedFile), "生成的测试文件应真实存在: " + generatedFile);
        String fileContent = Files.readString(generatedFile);
        assertTrue(fileContent.contains("package com.example;"), "文件应包含正确 package 声明");
        assertTrue(fileContent.contains("import org.junit.jupiter.api.Test"), "文件应导入 JUnit5 Test");
        assertTrue(fileContent.contains("class CalculatorTest"), "文件应包含 CalculatorTest 类");
        assertTrue(fileContent.contains("@Test"), "文件应包含 @Test 注解");
        assertTrue(fileContent.contains("Assertions.assertEquals(2, calculator.add(1, 1))"),
                "文件应包含调用 add(1,1) 且预期为 2 的断言");

        // ------------------------------ A4：执行测试 ------------------------------
        when(mavenProcessRunner.run(any(), any(String[].class), any(), anyInt()))
                .thenAnswer(IntegrationTestFixtures.mavenRunnerWritingReports());

        mockMvc.perform(post("/api/projects/{projectId}/tests/execute", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PASSED"))
                .andExpect(jsonPath("$.data.totalTests").value(1))
                .andExpect(jsonPath("$.data.passedTests").value(1))
                .andExpect(jsonPath("$.data.failedTests").value(0))
                .andExpect(jsonPath("$.data.skippedTests").value(0))
                .andExpect(jsonPath("$.data.success").value(true));

        // 报告解析结果来自真实 XML/文件：断言 Surefire 报告真实落盘
        Path surefireDir = extractedProjectDir.resolve("target").resolve("surefire-reports");
        assertTrue(Files.isDirectory(surefireDir), "surefire-reports 目录应存在");
        assertTrue(Files.exists(surefireDir.resolve("TEST-com.example.CalculatorTest.xml")),
                "应生成真实 Surefire XML 报告");
        assertTrue(Files.exists(surefireDir.resolve("com.example.CalculatorTest.txt")),
                "应生成真实 Surefire 汇总报告（解析统计来源）");

        // ------------------------------ A5：覆盖率分析 ------------------------------
        MvcResult covResult = mockMvc.perform(post("/api/projects/{projectId}/coverage", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.coverageStatus").value("COVERAGE_GENERATED"))
                .andReturn();
        JsonNode metrics = readJson(covResult).path("data").path("metrics");
        assertTrue(metrics.isArray() && metrics.size() >= 3, "应解析到 LINE/BRANCH/METHOD 等指标");

        // 百分比必须与构造的 jacoco.xml 数据一致（LINE 3/4=75.0、BRANCH 1/2=50.0、METHOD 3/4=75.0）
        assertCoverageMetric(metrics, "LINE", true, 1, 3, 75.0);
        assertCoverageMetric(metrics, "BRANCH", true, 1, 1, 50.0);
        assertCoverageMetric(metrics, "METHOD", true, 1, 3, 75.0);

        // 真实 jacoco.xml 文件存在且非空
        Path jacocoXml = extractedProjectDir.resolve("target").resolve("site")
                .resolve("jacoco").resolve("jacoco.xml");
        assertTrue(Files.exists(jacocoXml), "jacoco.xml 应真实存在");
        assertTrue(Files.size(jacocoXml) > 0, "jacoco.xml 应非空");

        // ------------------------------ A6：质量评估 ------------------------------
        // 质量评估内部会再次运行覆盖率，Mock Runner 为无状态 Answer，支持重复调用
        MvcResult qualResult = mockMvc.perform(post("/api/projects/{projectId}/quality", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode qualData = readJson(qualResult).path("data");

        // 静态分析统计：至少 1 个测试类、1 个测试方法，断言数量正确
        JsonNode staticStats = qualData.path("staticStats");
        assertEquals(1, staticStats.path("testClassCount").asInt(), "应统计到 1 个测试类");
        assertEquals(1, staticStats.path("totalTestMethods").asInt(), "应统计到 1 个测试方法");
        assertEquals(1, staticStats.path("totalAssertions").asInt(), "应统计到 1 个断言");
        assertEquals(0, staticStats.path("emptyTestMethods").asInt(), "应无空测试方法");
        assertEquals(0, staticStats.path("noAssertionTestMethods").asInt(), "应无无断言测试方法");
        assertEquals(0, staticStats.path("skippedTestMethods").asInt(), "应无跳过测试");
        assertEquals(0, staticStats.path("parseFailedFileCount").asInt(), "应无解析失败文件");
        JsonNode analysisError = staticStats.path("analysisError");
        assertTrue(analysisError.isNull() || analysisError.isMissingNode(),
                "静态分析应正常完成（analysisError 为 null），实际: " + analysisError);

        // 覆盖率状态
        assertEquals("COVERAGE_GENERATED",
                qualData.path("coverageResult").path("coverageStatus").asText(),
                "质量评估中的覆盖率状态应为 COVERAGE_GENERATED");

        // 质量报告综合评分可评（不得只用 overallScore != null 作为唯一判断）
        JsonNode qualityReport = qualData.path("qualityReport");
        assertFalse(qualityReport.isMissingNode(), "qualityReport 应存在");
        assertNotNull(qualityReport.path("overallScore").numberValue(),
                "综合评分应可评（非 null）");
        String grade = qualityReport.path("grade").asText();
        assertTrue(List.of("A", "B", "C", "D", "F").contains(grade),
                "等级应为 A/B/C/D/F 中的有效等级，而非 N/A，实际: " + grade);

        // 各维度可评状态与实际输入数据相符：PASSED + COVERAGE_GENERATED + 有效静态数据 → 4 维全可评
        JsonNode dimensions = qualityReport.path("dimensions");
        assertTrue(dimensions.isArray() && dimensions.size() == 4, "应返回 4 个评分维度");
        for (JsonNode dim : dimensions) {
            String type = dim.path("type").asText();
            assertTrue(dim.path("available").asBoolean(), "维度 " + type + " 应可评（数据有效）");
            Double score = dim.path("score").numberValue() == null
                    ? null : dim.path("score").asDouble();
            assertNotNull(score, "可评维度 " + type + " 的 score 不应为 null");
            assertTrue(score >= 0 && score <= 100,
                    "维度 " + type + " 评分应在 0~100 范围内，实际: " + score);
        }

        // 确定性分数断言：各维度实际分数必须与 QualityScorer 公式计算结果一致，
        // 防止评分公式被静默改坏（权重/阈值错误）时测试仍通过
        assertDimensionScore(dimensions, "EFFECTIVENESS", 100.0);
        assertDimensionScore(dimensions, "COVERAGE", 67.5);
        assertDimensionScore(dimensions, "SUFFICIENCY", 30.0);
        assertDimensionScore(dimensions, "CORRECTNESS", 100.0);

        // 综合分在合理范围
        double overall = qualityReport.path("overallScore").asDouble();
        assertTrue(overall >= 0 && overall <= 100, "综合分应在 0~100 范围内，实际: " + overall);

        // 综合分与等级精确断言：四维度加权 = 77.875，对应等级 B
        assertEquals(77.875, qualityReport.path("overallScore").asDouble(), 0.001,
                "综合分应等于四维度加权结果（100×0.30 + 67.5×0.25 + 30×0.20 + 100×0.25）");
        assertEquals("B", qualityReport.path("grade").asText(),
                "综合分 77.875 应对应等级 B");
    }

    // ============================== 用例 B：编译错误修复闭环 ==============================

    /**
     * 用例 B：真实"测试生成 → Maven 执行 → 编译错误反馈 → LLM 修复 → 再次执行成功"闭环。
     * <p>
     * 边界：使用真实 TestOptimizationServiceImpl、真实 TestExecutionServiceImpl
     * （不得 Mock TestExecutionService）、真实 ExecutionFeedbackExtractor / SurefireReportParser /
     * TestFileManager；仅 Mock LLMService 与 MavenProcessRunner 两个外部依赖。
     * <p>
     * 关键：第一次 Maven 调用返回 exitCode=1 且不写 surefire 报告，由真实
     * TestExecutionServiceImpl.buildTestResult 推导出 COMPILE_ERROR；输出中的编译错误行
     * 经真实 ExecutionFeedbackExtractor 判为 TEST_SOURCE，从而进入 LLM 修复；第二次执行
     * 写真实可解析报告 → PASSED。不得通过 Mock TestExecutionService 或直接伪造 COMPILE_ERROR 状态绕过。
     */
    @Test
    void optimizationClosedLoop_compileErrorThenRepair_passes() throws Exception {
        // ------------------------------ B0：上传 + 解析独立项目 ------------------------------
        byte[] zipBytes = IntegrationTestFixtures.buildProjectZip();
        MockMultipartFile file = new MockMultipartFile(
                "file", "calculator.zip", "application/zip", zipBytes);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").exists())
                .andReturn();
        String projectId = readJson(uploadResult).path("data").path("projectId").asText();
        Path extractedProjectDir = tempExtractedDir().resolve(projectId);

        mockMvc.perform(post("/api/projects/{projectId}/parse", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 结构守护：两个服务必须是真实 Bean（未被 Mock），优化服务才会注入真实执行服务
        assertFalse(Mockito.mockingDetails(testExecutionService).isMock(),
                "TestExecutionService 不得被 Mock，必须为真实 TestExecutionServiceImpl");
        assertFalse(Mockito.mockingDetails(testOptimizationService).isMock(),
                "TestOptimizationService 不得被 Mock，必须为真实实现");

        // 业务源码与 pom 快照（断言优化未修改它们）
        Path calculatorFile = extractedProjectDir.resolve("src/main/java/com/example/Calculator.java");
        Path pomFile = extractedProjectDir.resolve("pom.xml");
        String calculatorBefore = Files.readString(calculatorFile);
        String pomBefore = Files.readString(pomFile);

        // ------------------------------ B1：第一次 LLM 返回错误版本测试 ------------------------------
        // 序列返回：第 1 次（generate）→ 错误版本；第 2 次（optimize 修复）→ 修复版本
        when(llmService.chat(anyString()))
                .thenReturn(IntegrationTestFixtures.brokenCalculatorTestResponse())
                .thenReturn(IntegrationTestFixtures.llmResponseWithCalculatorTest());

        TestGenerationRequest genReq = new TestGenerationRequest();
        genReq.setClassName("Calculator");
        genReq.setMethodName("add");
        genReq.setParamTypes(List.of("int", "int"));

        mockMvc.perform(post("/api/projects/{projectId}/tests/generate", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("GENERATED"));

        // 断言：初次落盘的测试文件确实是错误版本（含不存在的 multiply 调用）
        Path testFile = extractedProjectDir.resolve("src/test/java/com/example/CalculatorTest.java");
        assertTrue(Files.exists(testFile), "生成的测试文件应存在: " + testFile);
        String initialTestContent = Files.readString(testFile);
        assertTrue(initialTestContent.contains("calculator.multiply(2, 3)"),
                "初次测试文件应包含不存在的 multiply 调用（错误版本）");
        assertFalse(initialTestContent.contains("calculator.add(1, 1)"),
                "初次测试文件不应是修复版本");

        // ------------------------------ B2：Mock Maven 执行（有状态） ------------------------------
        // 编译错误路径基于真实测试文件绝对路径构造，文件名 testFileName=CalculatorTest.java
        String compileErrorOutput = IntegrationTestFixtures.compileErrorOutput(testFile);
        when(mavenProcessRunner.run(any(), any(String[].class), any(), anyInt()))
                .thenAnswer(IntegrationTestFixtures.mavenRunnerCompileErrorThenSuccess(compileErrorOutput));

        // ------------------------------ B3：调用优化接口，触发真实闭环 ------------------------------
        TestOptimizationRequest optReq = new TestOptimizationRequest();
        optReq.setClassName("Calculator");
        optReq.setMethodName("add");
        optReq.setParamTypes(List.of("int", "int"));
        optReq.setMaxRetries(2);

        MvcResult optResult = mockMvc.perform(post("/api/projects/{projectId}/tests/optimize", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(optReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = readJson(optResult).path("data");

        // 断言：首次执行由真实 TestExecutionServiceImpl 推导出 COMPILE_ERROR（exitCode=1，无报告）
        assertEquals("COMPILE_ERROR", data.path("initialExecutionResult").path("status").asText(),
                "首次执行应由真实服务推导出 COMPILE_ERROR，不得直接伪造状态");
        assertEquals(1, data.path("initialExecutionResult").path("exitCode").asInt(),
                "首次执行 exitCode 应为 1");
        assertFalse(data.path("initialExecutionResult").path("success").asBoolean(),
                "首次执行 success 应为 false");

        // 断言：最终结果为 PASSED，且来自第二次执行（exitCode=0，区别于首次失败）
        assertTrue(data.path("optimizationSuccess").asBoolean(), "optimizationSuccess 应为 true");
        assertEquals("PASSED", data.path("finalExecutionResult").path("status").asText(),
                "最终执行状态应为 PASSED");
        assertEquals(0, data.path("finalExecutionResult").path("exitCode").asInt(),
                "最终执行 exitCode 应为 0（来自第二次成功执行）");
        // retryCount 与真实实现一致：maxRetries=2，第一轮修复即成功 → retryCount=1
        assertEquals(1, data.path("retryCount").asInt(),
                "retryCount 应为 1（一轮修复即成功）");

        // 断言：优化记录包含首次失败（COMPILE/TEST_SOURCE）与修复后执行（PASSED）
        JsonNode records = data.path("repairRecords");
        assertTrue(records.isArray() && records.size() == 1,
                "应恰好 1 条修复记录，实际: " + records);
        JsonNode record = records.get(0);
        assertEquals(1, record.path("retryNumber").asInt());
        assertEquals("COMPILE", record.path("failureType").asText(),
                "修复记录 failureType 应为 COMPILE");
        assertTrue(record.path("repaired").asBoolean(), "修复记录 repaired 应为 true");
        assertEquals("PASSED", record.path("executionResult").path("status").asText(),
                "修复记录中的执行结果应为 PASSED（修复后执行）");
        // 证明真实 ExecutionFeedbackExtractor 将来源识别为 TEST_SOURCE
        assertTrue(record.path("feedbackSummary").asText().contains("TEST_SOURCE"),
                "反馈摘要应标识编译错误来源为 TEST_SOURCE，实际: "
                        + record.path("feedbackSummary").asText());

        // 断言：最终测试文件是修复版本，不包含错误方法调用
        String finalTestContent = Files.readString(testFile);
        assertTrue(finalTestContent.contains("calculator.add(1, 1)"),
                "最终测试文件应为修复版本（调用 add）");
        assertFalse(finalTestContent.contains("multiply"),
                "最终测试文件不应包含不存在的 multiply 调用");
        assertNotEquals(initialTestContent, finalTestContent,
                "测试文件应已被修复版本覆盖");

        // 断言：业务源码、pom.xml 未被意外修改
        assertEquals(calculatorBefore, Files.readString(calculatorFile),
                "业务源码 Calculator.java 不得被修改");
        assertEquals(pomBefore, Files.readString(pomFile),
                "pom.xml 不得被修改");

        // 断言：LLM 恰好调用两次（generate 1 次 + optimize 修复 1 次）
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService, times(2)).chat(promptCaptor.capture());
        // 断言：第二次 LLM 请求包含第一次编译错误的具体内容（反馈传递链证据）
        String secondPrompt = promptCaptor.getAllValues().get(1);
        assertTrue(secondPrompt.contains("cannot find symbol"),
                "第二次 LLM prompt 应包含第一次编译错误 cannot find symbol");
        assertTrue(secondPrompt.contains("CalculatorTest.java"),
                "第二次 LLM prompt 应引用出错的测试文件 CalculatorTest.java");

        // 断言：MavenProcessRunner 恰好执行两次（首次失败 + 修复后成功）
        verify(mavenProcessRunner, times(2)).run(any(), any(String[].class), any(), anyInt());

        // 断言：最终执行结果对应第二次执行 —— surefire 报告来自第二次（每次执行前会被清理）
        Path surefireDir = extractedProjectDir.resolve("target").resolve("surefire-reports");
        assertTrue(Files.isDirectory(surefireDir), "surefire-reports 目录应存在（第二次执行生成）");
        assertTrue(Files.exists(surefireDir.resolve("com.example.CalculatorTest.txt")),
                "应存在第二次执行生成的真实 surefire 汇总报告");
        assertTrue(Files.exists(surefireDir.resolve("TEST-com.example.CalculatorTest.xml")),
                "应存在第二次执行生成的真实 surefire XML 报告");
        String summaryTxt = Files.readString(surefireDir.resolve("com.example.CalculatorTest.txt"));
        assertTrue(summaryTxt.contains("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"),
                "最终落盘的 surefire 汇总报告应为成功结果（来自第二次执行，而非首次失败）");
    }

    // ============================== 辅助方法 ==============================

    private Path tempProjectsDir() {
        return TEMP_STORAGE_ROOT.resolve("projects");
    }

    private Path tempExtractedDir() {
        return TEMP_STORAGE_ROOT.resolve("extracted");
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 断言某一覆盖率指标的真实解析结果：available / missed / covered / percentage
     * 必须与构造的 jacoco.xml 数据一致。
     */
    private void assertCoverageMetric(JsonNode metrics, String type, boolean expectedAvailable,
                                      int expectedMissed, int expectedCovered, Double expectedPercentage) {
        JsonNode found = null;
        for (JsonNode m : metrics) {
            if (type.equals(m.path("type").asText())) {
                found = m;
                break;
            }
        }
        assertNotNull(found, "应存在 " + type + " 覆盖率指标");
        assertEquals(expectedAvailable, found.path("available").asBoolean(),
                type + " 指标 available 状态不正确");
        assertEquals(expectedMissed, found.path("missed").asInt(),
                type + " 指标 missed 不正确");
        assertEquals(expectedCovered, found.path("covered").asInt(),
                type + " 指标 covered 不正确");
        if (expectedPercentage != null) {
            assertEquals(expectedPercentage, found.path("percentage").asDouble(), 0.001,
                    type + " 指标 percentage 与构造的 XML 数据不一致");
        }
    }

    /**
     * 按维度 type 查找并断言其确定性评分：available=true 且 score 与预期值误差不超过 0.001。
     * 找不到对应维度时通过 fail 明确失败，避免静默漏判。
     */
    private void assertDimensionScore(JsonNode dimensions, String type, double expected) {
        JsonNode found = null;
        for (JsonNode d : dimensions) {
            if (type.equals(d.path("type").asText())) {
                found = d;
                break;
            }
        }
        if (found == null) {
            fail("应存在评分维度: " + type);
        }
        assertTrue(found.path("available").asBoolean(), "维度 " + type + " 应可评");
        assertEquals(expected, found.path("score").asDouble(), 0.001,
                "维度 " + type + " 分数与 QualityScorer 确定性预期不符");
    }

    @AfterAll
    static void cleanupTempStorage() throws IOException {
        deleteRecursively(TEMP_STORAGE_ROOT);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响测试结论
                }
            });
        }
    }
}
