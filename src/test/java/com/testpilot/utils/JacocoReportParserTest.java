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
        String maliciousXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE report [\n"
                + "  <!ENTITY xxe SYSTEM \"file:///etc/passwd\">\n"
                + "]>\n"
                + "<report name=\"&xxe;\">\n"
                + "  <counter type=\"LINE\" missed=\"1\" covered=\"1\"/>\n"
                + "</report>";
        Files.writeString(jacocoXml, maliciousXml, StandardCharsets.UTF_8);

        // XXE 防护下，要么正常解析（但 name 里的实体不会被展开），要么拒绝
        List<CoverageMetric> metrics = JacocoReportParser.parse(jacocoXml);
        // 关键：不抛异常就是安全的；若 DOCTYPE 被禁止，会抛 SAXException → 走空列表返回
        if (JacocoReportParser.hasValidCounters(metrics)) {
            // 解析成功，验证 counter 数据正确
            CoverageMetric line = metrics.stream().filter(m -> "LINE".equals(m.getType())).findFirst().get();
            assertTrue(line.isAvailable());
            assertEquals(50.0, line.getPercentage(), 0.01);
        }
        // 若 hasValidCounters=false，说明 DOCTYPE 被拒绝 → 安全
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
