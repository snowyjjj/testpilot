package com.testpilot.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireReportParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parse_singleReport_sumsCorrectly() throws IOException {
        String report = """
                -------------------------------------------------------------------------------
                Test set: com.example.CalculatorTest
                -------------------------------------------------------------------------------
                Tests run: 5, Failures: 1, Errors: 0, Skipped: 1, Time elapsed: 0.123 sec
                """;
        Files.writeString(tempDir.resolve("com.example.CalculatorTest.txt"), report, StandardCharsets.UTF_8);

        SurefireReportParser.SurefireSummary summary = SurefireReportParser.parse(tempDir);

        assertTrue(summary.isReportFound());
        assertEquals(5, summary.getTotalTests());
        assertEquals(3, summary.getPassedTests()); // 5 - 1 failed - 1 skipped
        assertEquals(1, summary.getFailedTests());
        assertEquals(1, summary.getSkippedTests());
    }

    @Test
    void parse_multipleReports_aggregates() throws IOException {
        Files.writeString(tempDir.resolve("A.txt"),
                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("B.txt"),
                "Tests run: 7, Failures: 2, Errors: 1, Skipped: 0, Time elapsed: 0.2 sec\n",
                StandardCharsets.UTF_8);

        SurefireReportParser.SurefireSummary summary = SurefireReportParser.parse(tempDir);

        assertTrue(summary.isReportFound());
        assertEquals(10, summary.getTotalTests());
        assertEquals(7, summary.getPassedTests());  // 10 - 3 failed (2+1)
        assertEquals(3, summary.getFailedTests());   // failures + errors
        assertEquals(0, summary.getSkippedTests());
    }

    @Test
    void parse_directoryNotExists_returnsNotFound() {
        Path nonExistent = tempDir.resolve("no-such-dir");
        SurefireReportParser.SurefireSummary summary = SurefireReportParser.parse(nonExistent);

        assertFalse(summary.isReportFound());
        assertEquals(0, summary.getTotalTests());
    }

    @Test
    void parse_noTxtFiles_returnsNotFound() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "not a report", StandardCharsets.UTF_8);
        SurefireReportParser.SurefireSummary summary = SurefireReportParser.parse(tempDir);

        assertFalse(summary.isReportFound());
    }

    @Test
    void parse_reportWithoutSummaryLine_returnsNotFound() throws IOException {
        Files.writeString(tempDir.resolve("EmptyTest.txt"),
                "Some log lines without summary\n", StandardCharsets.UTF_8);

        SurefireReportParser.SurefireSummary summary = SurefireReportParser.parse(tempDir);

        assertFalse(summary.isReportFound());
    }

    // ============ XML 失败用例解析测试 ============

    @Test
    void parseXmlFailures_extractsFailureCase() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.CalculatorTest" tests="2" failures="1" errors="0">
                  <testcase classname="com.example.CalculatorTest" name="testAdd" time="0.001">
                    <failure type="java.lang.AssertionError" message="expected:&lt;3&gt; but was:&lt;5&gt;">
                java.lang.AssertionError: expected:&lt;3&gt; but was:&lt;5&gt;
                    at com.example.CalculatorTest.testAdd(CalculatorTest.java:12)
                    </failure>
                  </testcase>
                  <testcase classname="com.example.CalculatorTest" name="testSub" time="0.001"/>
                </testsuite>
                """;
        Files.writeString(tempDir.resolve("TEST-com.example.CalculatorTest.xml"), xml, StandardCharsets.UTF_8);

        var failures = SurefireReportParser.parseXmlFailures(tempDir, 500);

        assertEquals(1, failures.size());
        SurefireReportParser.FailedCase fc = failures.get(0);
        assertEquals("com.example.CalculatorTest", fc.getClassName());
        assertEquals("testAdd", fc.getMethodName());
        assertEquals("java.lang.AssertionError", fc.getExceptionType());
        assertTrue(fc.getMessage().contains("expected"));
        assertTrue(fc.getStackTrace().contains("CalculatorTest.java:12"));
    }

    @Test
    void parseXmlFailures_extractsErrorCase() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.CalculatorTest" tests="1" failures="0" errors="1">
                  <testcase classname="com.example.CalculatorTest" name="testCrash" time="0.001">
                    <error type="java.lang.NullPointerException" message="null"/>
                  </testcase>
                </testsuite>
                """;
        Files.writeString(tempDir.resolve("TEST-com.example.CalculatorTest.xml"), xml, StandardCharsets.UTF_8);

        var failures = SurefireReportParser.parseXmlFailures(tempDir, 500);

        assertEquals(1, failures.size());
        assertEquals("java.lang.NullPointerException", failures.get(0).getExceptionType());
    }

    @Test
    void parseXmlFailures_longStackTrace_isTruncated() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("at com.example.X.line(").append(i).append(")\n");
        }
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite tests="1" failures="1" errors="0">
                  <testcase classname="C" name="m">
                    <failure type="java.lang.AssertionError">%s</failure>
                  </testcase>
                </testsuite>
                """.formatted(sb);
        Files.writeString(tempDir.resolve("TEST-C.xml"), xml, StandardCharsets.UTF_8);

        var failures = SurefireReportParser.parseXmlFailures(tempDir, 100);

        assertEquals(1, failures.size());
        // 截断后长度应 <= 限制 + 截断后缀长度
        assertTrue(failures.get(0).getStackTrace().length() <= 100 + 30);
        assertTrue(failures.get(0).getStackTrace().contains("truncated"));
    }

    @Test
    void parseXmlFailures_xxeAttack_safe() throws IOException {
        // 恶意 XXE XML：尝试读取外部实体
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <testsuite tests="1" failures="1" errors="0">
                  <testcase classname="C" name="m">
                    <failure type="E">&xxe;</failure>
                  </testcase>
                </testsuite>
                """;
        Files.writeString(tempDir.resolve("TEST-C.xml"), xml, StandardCharsets.UTF_8);

        // 解析应安全失败（空列表或抛异常被吞），不能加载外部实体
        var failures = SurefireReportParser.parseXmlFailures(tempDir, 500);
        assertTrue(failures.isEmpty());
    }

    @Test
    void parseXmlFailures_noXmlFiles_returnsEmpty() {
        assertTrue(SurefireReportParser.parseXmlFailures(tempDir, 500).isEmpty());
    }
}
