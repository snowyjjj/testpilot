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
}
