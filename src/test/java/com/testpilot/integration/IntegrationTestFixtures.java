package com.testpilot.integration;

import com.testpilot.utils.ProcessResult;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 阶段8 集成测试的数据与 Mock 辅助方法。
 * <p>
 * 所有报告文件格式均复用现有单元测试（{@code TestExecutionServiceImplTest}）已验证的合法结构，
 * 确保能被真实的 {@code SurefireReportParser} / {@code JacocoReportParser} 正确解析。
 * <p>
 * 本类只负责构造测试夹具，不包含任何断言；断言在 {@link SystemFlowIntegrationTest} 中完成。
 */
final class IntegrationTestFixtures {

    private IntegrationTestFixtures() {
    }

    // ============================== 被测项目 ZIP ==============================

    /**
     * 构造真实 ZIP 字节，包含根 pom.xml 与 Calculator.java。
     * pom.xml 声明 JUnit 5 依赖（org.junit.jupiter），无 DOCTYPE 声明
     * （JUnitDetector 启用 disallow-doctype-decl，必须无 DOCTYPE 才能解析）。
     */
    static byte[] buildProjectZip() throws IOException {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("pom.xml", pomXmlContent());
        entries.put("src/main/java/com/example/Calculator.java", calculatorSource());
        return createZipWithContents(entries);
    }

    /** 最小 pom.xml：声明 junit-jupiter，触发 JUnitDetector 判定为 JUNIT5。 */
    static String pomXmlContent() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>com.example</groupId>\n"
                + "    <artifactId>calculator</artifactId>\n"
                + "    <version>1.0.0</version>\n"
                + "    <properties>\n"
                + "        <maven.compiler.source>17</maven.compiler.source>\n"
                + "        <maven.compiler.target>17</maven.compiler.target>\n"
                + "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "    </properties>\n"
                + "    <dependencies>\n"
                + "        <dependency>\n"
                + "            <groupId>org.junit.jupiter</groupId>\n"
                + "            <artifactId>junit-jupiter</artifactId>\n"
                + "            <version>5.10.0</version>\n"
                + "            <scope>test</scope>\n"
                + "        </dependency>\n"
                + "    </dependencies>\n"
                + "</project>\n";
    }

    /** Calculator 源码：包含 public int add(int a, int b)。 */
    static String calculatorSource() {
        return "package com.example;\n\n"
                + "public class Calculator {\n"
                + "    public int add(int a, int b) {\n"
                + "        return a + b;\n"
                + "    }\n"
                + "}\n";
    }

    private static byte[] createZipWithContents(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    // ============================== LLM 生成的测试代码 ==============================

    /**
     * LLM 返回内容：用 ```java ... ``` 包裹，供 PromptBuilder.extractJavaCode 提取。
     * 满足 PromptBuilder.validateGeneratedCode(JUNIT5) 的全部校验：
     * package 声明、class 声明、@Test、import org.junit.jupiter.api.Test。
     * 同时 TestQualityAnalyzer 能识别 1 个测试方法与 1 个 JUnit5 断言（Assertions.assertEquals）。
     */
    static String llmResponseWithCalculatorTest() {
        return "```java\n" + calculatorTestSource() + "\n```";
    }

    /** 提取后的纯测试代码（用于文件内容断言）。 */
    static String calculatorTestSource() {
        return "package com.example;\n\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "import org.junit.jupiter.api.Assertions;\n\n"
                + "public class CalculatorTest {\n"
                + "    @Test\n"
                + "    public void testAdd() {\n"
                + "        Calculator calculator = new Calculator();\n"
                + "        Assertions.assertEquals(2, calculator.add(1, 1));\n"
                + "    }\n"
                + "}\n";
    }

    /**
     * 语法合法但调用不存在方法（multiply）的 JUnit5 测试代码：
     * 能通过 PromptBuilder.validateGeneratedCode 的静态校验（package/class/@Test/import），
     * 但真实编译会失败（cannot find symbol），用于触发优化修复闭环。
     */
    static String brokenCalculatorTestSource() {
        return "package com.example;\n\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "import org.junit.jupiter.api.Assertions;\n\n"
                + "public class CalculatorTest {\n"
                + "    @Test\n"
                + "    public void testAdd() {\n"
                + "        Calculator calculator = new Calculator();\n"
                + "        Assertions.assertEquals(5, calculator.multiply(2, 3));\n"
                + "    }\n"
                + "}\n";
    }

    /** LLM 返回：用 ```java ... ``` 包裹的错误版本测试代码。 */
    static String brokenCalculatorTestResponse() {
        return "```java\n" + brokenCalculatorTestSource() + "\n```";
    }

    // ============================== Surefire 报告 ==============================

    /**
     * Surefire 汇总 .txt 内容（SurefireReportParser 只读 .txt 汇总统计）。
     * 格式匹配正则：Tests run: N, Failures: N, Errors: N, Skipped: N。
     */
    static String surefireSummaryTxt() {
        return "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n";
    }

    /**
     * Surefire XML 报告（TEST-com.example.CalculatorTest.xml）。
     * SurefireReportParser 启用 disallow-doctype-decl，故无 DOCTYPE 声明。
     * 表示 tests=1 / failures=0 / errors=0 / skipped=0。
     */
    static String surefireXmlReport() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"com.example.CalculatorTest\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0.1\">\n"
                + "    <testcase name=\"testAdd\" classname=\"com.example.CalculatorTest\" time=\"0.1\"/>\n"
                + "</testsuite>\n";
    }

    // ============================== JaCoCo 报告 ==============================

    /**
     * 复用 TestExecutionServiceImplTest.validJacocoXml() 已验证的合法格式：
     * 根 <report> 下的直接子级 <counter>，无 DOCTYPE。
     * LINE 1/3 → 75.0%；BRANCH 1/1 → 50.0%；METHOD 1/3 → 75.0%。
     */
    static String validJacocoXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<report name=\"test\">\n"
                + "  <counter type=\"INSTRUCTION\" missed=\"6\" covered=\"12\"/>\n"
                + "  <counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/>\n"
                + "  <counter type=\"LINE\" missed=\"1\" covered=\"3\"/>\n"
                + "  <counter type=\"METHOD\" missed=\"1\" covered=\"3\"/>\n"
                + "  <counter type=\"CLASS\" missed=\"0\" covered=\"1\"/>\n"
                + "</report>";
    }

    // ============================== Mock MavenProcessRunner ==============================

    private static final ProcessResult SUCCESS_TEST =
            new ProcessResult(0, "BUILD SUCCESS", "", false, 1000L);
    private static final ProcessResult SUCCESS_REPORT =
            new ProcessResult(0, "REPORT OK", "", false, 200L);

    /**
     * 无状态 Answer：每次 run() 调用都根据 args 向 workingDir 写入真实报告文件，
     * 支持重复调用（质量评估会再次触发覆盖率执行），不依赖调用次数。
     * <ul>
     *   <li>prepare-agent 调用：写 surefire 报告 + 非空 target/jacoco.exec</li>
     *   <li>report 调用：写 target/site/jacoco/jacoco.xml</li>
     *   <li>普通 test 调用：写 surefire 报告</li>
     * </ul>
     */
    static Answer<ProcessResult> mavenRunnerWritingReports() {
        return (InvocationOnMock invocation) -> {
            Path mavenDir = invocation.getArgument(0);
            String[] args = invocation.getArgument(1);
            String firstArg = (args != null && args.length > 0) ? args[0] : "";

            boolean prepareAgent = firstArg.contains("prepare-agent");
            boolean reportCall = firstArg.contains("jacoco-maven-plugin") && firstArg.contains(":report");

            // 执行前 SurefireReportParser/Jacoco 报告会被清理，故每次重写
            writeSurefireReports(mavenDir);

            if (prepareAgent) {
                writeJacocoExec(mavenDir);
                return SUCCESS_TEST;
            }
            if (reportCall) {
                writeJacocoXml(mavenDir);
                return SUCCESS_REPORT;
            }
            return SUCCESS_TEST;
        };
    }

    /**
     * 第一次 Maven 执行的 stdout：含真实 javac 编译错误格式，且错误路径指向目标测试文件。
     * <p>
     * 必须满足 ExecutionFeedbackExtractor 判为 TEST_SOURCE 的全部条件：
     * <ul>
     *   <li>含 {@code [ERROR] .../src/test/java/.../CalculatorTest.java:[line,col] cannot find symbol}</li>
     *   <li>文件名与优化服务传入的 testFileName 一致（基于真实测试文件绝对路径构造）</li>
     *   <li>不含 /src/main/java/、依赖解析关键词、非 compiler 插件错误</li>
     * </ul>
     */
    static String compileErrorOutput(Path testFile) {
        String path = testFile.toAbsolutePath().toString().replace('\\', '/');
        return "[ERROR] " + path + ":[10,39] cannot find symbol\n"
                + "[ERROR]   symbol:   method multiply(int,int)\n"
                + "[ERROR]   location: class com.example.CalculatorTest\n";
    }

    /**
     * 有状态 Answer，用于用例 B 的编译错误修复闭环：
     * <ul>
     *   <li>第 1 次调用：返回 exitCode=1 的编译失败结果，且<b>不写任何 surefire 报告</b>，
     *       使真实 TestExecutionServiceImpl.buildTestResult 推导出 COMPILE_ERROR。</li>
     *   <li>第 2 次及以后调用：写入真实可解析的 surefire 报告，返回成功退出码，
     *       使真实服务推导出 PASSED。</li>
     * </ul>
     * 不能复用 {@link #mavenRunnerWritingReports()}：它无条件写报告，会导致第一次执行
     * 因 reportFound=true 被误判为 MAVEN_ERROR 而非 COMPILE_ERROR。
     *
     * @param compileErrorOutput 第一次执行返回的 stdout（含真实格式的编译错误行）
     */
    static Answer<ProcessResult> mavenRunnerCompileErrorThenSuccess(String compileErrorOutput) {
        final AtomicInteger callCount = new AtomicInteger(0);
        final ProcessResult compileFailResult =
                new ProcessResult(1, compileErrorOutput, "", false, 800L);
        return (InvocationOnMock invocation) -> {
            int n = callCount.incrementAndGet();
            Path mavenDir = invocation.getArgument(0);
            if (n == 1) {
                // 第一次：编译失败，不生成任何有效 surefire 报告
                return compileFailResult;
            }
            // 第二次：写真实 surefire 报告（.txt + .xml），返回成功
            writeSurefireReports(mavenDir);
            return SUCCESS_TEST;
        };
    }

    private static void writeSurefireReports(Path mavenDir) throws IOException {
        Path reportsDir = mavenDir.resolve("target").resolve("surefire-reports");
        Files.createDirectories(reportsDir);
        // .txt：SurefireReportParser.parse 汇总统计的唯一来源
        Files.writeString(reportsDir.resolve("com.example.CalculatorTest.txt"),
                surefireSummaryTxt(), StandardCharsets.UTF_8);
        // TEST-*.xml：真实 XML 报告（parseXmlFailures 读取），无 DOCTYPE
        Files.writeString(reportsDir.resolve("TEST-com.example.CalculatorTest.xml"),
                surefireXmlReport(), StandardCharsets.UTF_8);
    }

    private static void writeJacocoExec(Path mavenDir) throws IOException {
        Path jacocoExec = mavenDir.resolve("target").resolve("jacoco.exec");
        Files.createDirectories(jacocoExec.getParent());
        Files.write(jacocoExec, new byte[]{1, 2, 3, 4, 5});
    }

    private static void writeJacocoXml(Path mavenDir) throws IOException {
        Path jacocoXml = mavenDir.resolve("target").resolve("site").resolve("jacoco").resolve("jacoco.xml");
        Files.createDirectories(jacocoXml.getParent());
        Files.writeString(jacocoXml, validJacocoXml(), StandardCharsets.UTF_8);
    }
}
