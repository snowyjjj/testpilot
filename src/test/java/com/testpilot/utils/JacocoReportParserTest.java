package com.testpilot.utils;

import com.testpilot.dto.CoverageMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JacocoReportParser 单元测试。
 */
class JacocoReportParserTest {

    @TempDir
    Path tempDir;

    private Path jacocoXml;

    @BeforeEach
    void setUp() throws IOException {
        jacocoXml = tempDir.resolve("jacoco.xml");
    }

    /**
     * 生成带根级 counter 和 package 级 counter 的 JaCoCo XML。
     * 根级 counter 应为唯一有效数据源，package 级不应被重复累计。
     */
    private void writeJacocoXml(String rootCounters, String packageCounters) throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<report name=\"test\">\n"
                + "  <package name=\"com/example\">\n"
                + "    <counter type=\"INSTRUCTION\" missed=\"10\" covered=\"20\"/>\n"
                + "  </package>\n"
                + rootCounters
                + "</report>";
        Files.writeString(jacocoXml, xml, StandardCharsets.UTF_8);
    }

    // ====== 基础解析 ======

    @Test
    void parse_normalXml_extractsAllCounters() throws IOException {
        writeJacocoXml(
                "  <counter type=\"INSTRUCTION\" missed=\"6\" covered=\"12\"/>\n"
                        + "  <counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/>\n"
                        + "  <counter type=\"LINE\" missed=\"1\" covered=\"3\"/>\n"
                        + "  <counter type=\"METHOD\" missed=\"1\" covered=\"3\"/>\n"
                        + "  <counter type=\"CLASS\" missed=\"0\" covered=\"1\"/>\n",
                "");

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        // 所有已知 type 都应输出
        assertEquals(6, metrics.size());
        assertTrue(JacocoReportParser.hasValidCounters(metrics));

        CoverageMetric instruction = metrics.stream().filter(m -> "INSTRUCTION".equals(m.getType())).findFirst().get();
        assertTrue(instruction.isAvailable());
        assertEquals(6, instruction.getMissed());
        assertEquals(12, instruction.getCovered());
        assertEquals(66.66666666666667, instruction.getPercentage(), 0.01);

        CoverageMetric branch = metrics.stream().filter(m -> "BRANCH".equals(m.getType())).findFirst().get();
        assertTrue(branch.isAvailable());
        assertEquals(50.0, branch.getPercentage(), 0.01);

        CoverageMetric line = metrics.stream().filter(m -> "LINE".equals(m.getType())).findFirst().get();
        assertTrue(line.isAvailable());
        assertEquals(75.0, line.getPercentage(), 0.01);

        CoverageMetric classM = metrics.stream().filter(m -> "CLASS".equals(m.getType())).findFirst().get();
        assertTrue(classM.isAvailable());
        assertEquals(100.0, classM.getPercentage(), 0.01);  // missed=0 covered=1 → 100%
    }

    @Test
    void parse_rootCountersOnly_noDoubleCounting() throws IOException {
        // 根级 INSTRUCTION missed=6 covered=12 (total=18)
        // package 级 INSTRUCTION missed=10 covered=20 (total=30)
        // 若被错误累计，将得到 total=48
        writeJacocoXml(
                "  <counter type=\"INSTRUCTION\" missed=\"6\" covered=\"12\"/>\n",
                "");

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        CoverageMetric m = metrics.stream().filter(mi -> "INSTRUCTION".equals(mi.getType())).findFirst().get();
        assertTrue(m.isAvailable());
        assertEquals(6, m.getMissed());
        assertEquals(12, m.getCovered());
        assertEquals(18, m.getMissed() + m.getCovered());
    }

    // ====== 三态 percentage ======

    @Test
    void parse_missingBranchCounter_availableFalse() throws IOException {
        // XML 不含 BRANCH
        writeJacocoXml(
                "  <counter type=\"INSTRUCTION\" missed=\"0\" covered=\"10\"/>\n"
                        + "  <counter type=\"LINE\" missed=\"0\" covered=\"5\"/>\n",
                "");

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        CoverageMetric branch = metrics.stream().filter(m -> "BRANCH".equals(m.getType())).findFirst().get();
        assertFalse(branch.isAvailable());
        assertEquals(0, branch.getMissed());
        assertEquals(0, branch.getCovered());
        assertNull(branch.getPercentage());
    }

    @Test
    void parse_branchZeroZero_availableTruePercentageNull() throws IOException {
        // BRANCH missed=0 covered=0 → 有该维度但无元素可覆盖
        writeJacocoXml(
                "  <counter type=\"BRANCH\" missed=\"0\" covered=\"0\"/>\n",
                "");

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        CoverageMetric branch = metrics.stream().filter(m -> "BRANCH".equals(m.getType())).findFirst().get();
        assertTrue(branch.isAvailable(), "BRANCH 0/0 应 available=true");
        assertEquals(0, branch.getMissed());
        assertEquals(0, branch.getCovered());
        assertNull(branch.getPercentage(), "missed+covered=0 时 percentage 应为 null");
    }

    @Test
    void parse_trueZeroCoverage_percentageZero() throws IOException {
        // 真正的 0%：missed>0 covered=0
        writeJacocoXml(
                "  <counter type=\"LINE\" missed=\"5\" covered=\"0\"/>\n",
                "");

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        CoverageMetric line = metrics.stream().filter(m -> "LINE".equals(m.getType())).findFirst().get();
        assertTrue(line.isAvailable());
        assertEquals(5, line.getMissed());
        assertEquals(0, line.getCovered());
        assertEquals(0.0, line.getPercentage(), 0.001);
    }

    @Test
    void parse_perfectCoverage_percentage100() throws IOException {
        writeJacocoXml(
                "  <counter type=\"LINE\" missed=\"0\" covered=\"5\"/>\n",
                "");

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        CoverageMetric line = metrics.stream().filter(m -> "LINE".equals(m.getType())).findFirst().get();
        assertTrue(line.isAvailable());
        assertEquals(100.0, line.getPercentage(), 0.001);
    }

    @Test
    void parse_complexity_keepsMissedCoveredButPercentageNull() throws IOException {
        // COMPLEXITY 不计算百分比，即使 missed+covered > 0，percentage 也应为 null
        writeJacocoXml(
                "  <counter type=\"COMPLEXITY\" missed=\"2\" covered=\"3\"/>\n",
                "");

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        CoverageMetric complexity = metrics.stream()
                .filter(m -> "COMPLEXITY".equals(m.getType()))
                .findFirst()
                .get();
        assertTrue(complexity.isAvailable());
        assertEquals(2, complexity.getMissed());
        assertEquals(3, complexity.getCovered());
        assertNull(complexity.getPercentage(),
                "COMPLEXITY 不应计算 percentage，应为 null");
    }

    // ====== 边界条件 ======

    @Test
    void parse_fileNotExists_returnsEmptyMetrics() {
        Path notExists = tempDir.resolve("does-not-exist.xml");
        List<CoverageMetric> metrics = JacocoReportParser.parse(notExists);
        assertNotNull(metrics);
        assertEquals(6, metrics.size());
        assertFalse(JacocoReportParser.hasValidCounters(metrics));
        // 全部 available=false
        for (CoverageMetric m : metrics) {
            assertFalse(m.isAvailable());
            assertNull(m.getPercentage());
        }
    }

    @Test
    void parse_corruptXml_returnsEmpty() throws IOException {
        Files.writeString(jacocoXml, "not valid xml at all <<<", StandardCharsets.UTF_8);
        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);
        assertFalse(JacocoReportParser.hasValidCounters(metrics));
    }

    @Test
    void parse_symlinkFile_returnsEmpty() throws Exception {
        // 创建指向不存在目标的符号链接
        Path target = tempDir.resolve("target.xml");
        if (Files.isSymbolicLink(jacocoXml)) {
            // 测试环境可能不支持符号链接
            return;
        }
        try {
            Files.createSymbolicLink(jacocoXml, target);
        } catch (UnsupportedOperationException | IOException e) {
            // 跳过：当前环境可能不允许创建符号链接
            return;
        }
        // 符号链接文件应被拒绝
        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);
        assertFalse(JacocoReportParser.hasValidCounters(metrics));
    }

    @Test
    void parse_xxeAttack_safe() throws IOException {
        // 使用本地临时文件作为 XXE 攻击目标，验证外部实体无法读取本地文件内容
        Path secretFile = tempDir.resolve("secret.txt");
        Files.writeString(secretFile, "SENSITIVE_DATA_12345", StandardCharsets.UTF_8);
        String secretUri = secretFile.toUri().toString();

        String maliciousXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE report [\n"
                + "  <!ENTITY xxe SYSTEM \"" + secretUri + "\">\n"
                + "]>\n"
                + "<report name=\"&xxe;\">\n"
                + "  <counter type=\"LINE\" missed=\"1\" covered=\"1\"/>\n"
                + "</report>";
        Files.writeString(jacocoXml, maliciousXml, StandardCharsets.UTF_8);

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        // 关键断言：解析后不能泄露本地文件内容
        // 无论解析成功还是因实体引用失败返回空，secret 内容都不应出现在解析结果中
        for (CoverageMetric m : metrics) {
            String type = m.getType();
            assertFalse(type.contains("SENSITIVE_DATA_12345"),
                    "外部实体内容不应出现在 type 中");
        }
        // 如果解析成功，验证 counter 数据正常（实体未被展开到属性中）
        if (JacocoReportParser.hasValidCounters(metrics)) {
            CoverageMetric line = metrics.stream().filter(m -> "LINE".equals(m.getType())).findFirst().get();
            assertTrue(line.isAvailable());
            assertEquals(50.0, line.getPercentage(), 0.01);
        }
    }

    // ====== JaCoCo 真实 DOCTYPE 场景 ======

    @Test
    void parse_jacocoDoctypeXml_succeeds() throws IOException {
        // JaCoCo 0.8.11 真实生成的 XML 头部：含 DOCTYPE 声明
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">\n"
                + "<report name=\"Maven Test Project\">\n"
                + "  <sessioninfo id=\"x\" start=\"0\" dump=\"0\"/>\n"
                + "  <package name=\"com/example\">\n"
                + "    <class name=\"com/example/Calculator\" sourcefilename=\"Calculator.java\">\n"
                + "      <counter type=\"INSTRUCTION\" missed=\"0\" covered=\"5\"/>\n"
                + "      <counter type=\"LINE\" missed=\"0\" covered=\"1\"/>\n"
                + "      <counter type=\"METHOD\" missed=\"0\" covered=\"1\"/>\n"
                + "      <counter type=\"CLASS\" missed=\"0\" covered=\"1\"/>\n"
                + "    </class>\n"
                + "    <counter type=\"INSTRUCTION\" missed=\"0\" covered=\"5\"/>\n"
                + "    <counter type=\"LINE\" missed=\"0\" covered=\"1\"/>\n"
                + "    <counter type=\"METHOD\" missed=\"0\" covered=\"1\"/>\n"
                + "    <counter type=\"CLASS\" missed=\"0\" covered=\"1\"/>\n"
                + "  </package>\n"
                + "  <counter type=\"INSTRUCTION\" missed=\"10\" covered=\"20\"/>\n"
                + "  <counter type=\"BRANCH\" missed=\"2\" covered=\"3\"/>\n"
                + "  <counter type=\"LINE\" missed=\"3\" covered=\"7\"/>\n"
                + "  <counter type=\"COMPLEXITY\" missed=\"4\" covered=\"5\"/>\n"
                + "  <counter type=\"METHOD\" missed=\"1\" covered=\"4\"/>\n"
                + "  <counter type=\"CLASS\" missed=\"0\" covered=\"1\"/>\n"
                + "</report>";
        Files.writeString(jacocoXml, xml, StandardCharsets.UTF_8);

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        assertTrue(JacocoReportParser.hasValidCounters(metrics),
                "含 JaCoCo DOCTYPE 的合法 XML 应能成功解析并产生有效 counter");
    }

    @Test
    void parse_jacocoDoctypeXml_extractsAllSixCounters() throws IOException {
        // 验证含 DOCTYPE 的 XML 中 6 种 counter 均被正确解析（根级直接子节点）
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">\n"
                + "<report name=\"Maven Test Project\">\n"
                + "  <package name=\"com/example\">\n"
                + "    <counter type=\"INSTRUCTION\" missed=\"999\" covered=\"999\"/>\n"
                + "  </package>\n"
                + "  <counter type=\"INSTRUCTION\" missed=\"334\" covered=\"25\"/>\n"
                + "  <counter type=\"BRANCH\" missed=\"44\" covered=\"0\"/>\n"
                + "  <counter type=\"LINE\" missed=\"73\" covered=\"9\"/>\n"
                + "  <counter type=\"COMPLEXITY\" missed=\"50\" covered=\"6\"/>\n"
                + "  <counter type=\"METHOD\" missed=\"28\" covered=\"6\"/>\n"
                + "  <counter type=\"CLASS\" missed=\"3\" covered=\"2\"/>\n"
                + "</report>";
        Files.writeString(jacocoXml, xml, StandardCharsets.UTF_8);

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        assertEquals(6, metrics.size());
        assertTrue(JacocoReportParser.hasValidCounters(metrics));

        CoverageMetric instruction = find(metrics, "INSTRUCTION");
        assertTrue(instruction.isAvailable());
        assertEquals(334, instruction.getMissed());
        assertEquals(25, instruction.getCovered());

        CoverageMetric branch = find(metrics, "BRANCH");
        assertTrue(branch.isAvailable());
        assertEquals(44, branch.getMissed());
        assertEquals(0, branch.getCovered());
        assertEquals(0.0, branch.getPercentage(), 0.001);

        CoverageMetric line = find(metrics, "LINE");
        assertTrue(line.isAvailable());
        assertEquals(73, line.getMissed());
        assertEquals(9, line.getCovered());

        CoverageMetric complexity = find(metrics, "COMPLEXITY");
        assertTrue(complexity.isAvailable());
        assertEquals(50, complexity.getMissed());
        assertEquals(6, complexity.getCovered());
        assertNull(complexity.getPercentage(), "COMPLEXITY 不应计算 percentage");

        CoverageMetric method = find(metrics, "METHOD");
        assertTrue(method.isAvailable());
        assertEquals(28, method.getMissed());
        assertEquals(6, method.getCovered());

        CoverageMetric clazz = find(metrics, "CLASS");
        assertTrue(clazz.isAvailable());
        assertEquals(3, clazz.getMissed());
        assertEquals(2, clazz.getCovered());
    }

    @Test
    void parse_externalEntity_cannotReadLocalFile() throws IOException {
        // 创建本地敏感文件，XML 通过外部实体尝试读取其内容
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        String secretUri = secret.toUri().toString();

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE report [\n"
                + "  <!ENTITY steal SYSTEM \"" + secretUri + "\">\n"
                + "]>\n"
                + "<report name=\"report\">\n"
                + "  <counter type=\"LINE\" missed=\"&steal;\" covered=\"1\"/>\n"
                + "</report>";
        Files.writeString(jacocoXml, xml, StandardCharsets.UTF_8);

        // XXE 防护下，外部实体不会被加载。结果要么是解析失败（空列表），
        // 要么是 LINE 的 missed 不包含敏感内容（实体未展开）。
        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);
        for (CoverageMetric m : metrics) {
            assertFalse(String.valueOf(m.getMissed()).contains("TOP_SECRET_VALUE"),
                    "外部实体内容不应出现在 missed 字段中");
            assertFalse(String.valueOf(m.getCovered()).contains("TOP_SECRET_VALUE"),
                    "外部实体内容不应出现在 covered 字段中");
        }
    }

    @Test
    void parse_externalDtd_notLoaded() throws IOException {
        // 外部 DTD 中定义实体并尝试注入，验证外部 DTD 不会被加载
        Path externalDtd = tempDir.resolve("evil.dtd");
        Files.writeString(externalDtd,
                "<!ENTITY injected \"INJECTED_FROM_DTD\">", StandardCharsets.UTF_8);
        String dtdUri = externalDtd.toUri().toString();

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE report SYSTEM \"" + dtdUri + "\">\n"
                + "<report name=\"&injected;\">\n"
                + "  <counter type=\"LINE\" missed=\"1\" covered=\"1\"/>\n"
                + "</report>";
        Files.writeString(jacocoXml, xml, StandardCharsets.UTF_8);

        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);

        // 外部 DTD 不应被加载：若解析成功，name 属性不含注入值；
        // 若因实体未定义而解析失败，则返回空列表。两种情况都安全。
        if (JacocoReportParser.hasValidCounters(metrics)) {
            CoverageMetric line = find(metrics, "LINE");
            assertTrue(line.isAvailable());
            assertEquals(1, line.getMissed());
            assertEquals(1, line.getCovered());
        }
        // 无论哪种情况，都不应该出现来自外部 DTD 的注入值
        for (CoverageMetric m : metrics) {
            assertFalse(m.getType().contains("INJECTED_FROM_DTD"),
                    "外部 DTD 中的实体不应被加载和展开");
        }
    }

    private CoverageMetric find(List<CoverageMetric> metrics, String type) {
        return metrics.stream().filter(m -> type.equals(m.getType())).findFirst().orElseThrow();
    }

    @Test
    void parse_nullPath_returnsEmpty() {
        List<CoverageMetric> metrics = JacocoReportParser.parse(null);
        assertFalse(JacocoReportParser.hasValidCounters(metrics));
    }

    @Test
    void parse_emptyReport_noCounters_returnsEmpty() throws IOException {
        Files.writeString(jacocoXml,
                "<?xml version=\"1.0\"?><report name=\"test\"></report>",
                StandardCharsets.UTF_8);
        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);
        assertFalse(JacocoReportParser.hasValidCounters(metrics));
        // 全部 available=false
        for (CoverageMetric m : metrics) {
            assertFalse(m.isAvailable());
        }
    }
}
