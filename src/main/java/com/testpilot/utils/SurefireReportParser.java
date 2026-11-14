package com.testpilot.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 解析 Maven Surefire 测试报告（target/surefire-reports/*.txt）。
 * <p>
 * Surefire 文本报告末尾包含形如：
 * <pre>
 * Tests run: 10, Failures: 2, Errors: 1, Skipped: 0, Time elapsed: 0.5 sec
 * </pre>
 * 的统计行，本类据此汇总 totalTests / passedTests / failedTests / skippedTests。
 */
public final class SurefireReportParser {

    private static final Logger log = LoggerFactory.getLogger(SurefireReportParser.class);

    // 匹配 "Tests run: X, Failures: Y, Errors: Z, Skipped: W"
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    private SurefireReportParser() {
    }

    /**
     * 解析指定 Surefire 报告目录下所有 .txt 文件并汇总统计。
     *
     * @param reportsDir target/surefire-reports 目录
     * @return 汇总结果；若目录不存在或无报告文件，reportFound 为 false
     */
    public static SurefireSummary parse(Path reportsDir) {
        if (reportsDir == null || !Files.isDirectory(reportsDir)) {
            log.debug("Surefire 报告目录不存在: {}", reportsDir);
            return new SurefireSummary(false, 0, 0, 0, 0);
        }

        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        boolean found = false;

        try (Stream<Path> files = Files.list(reportsDir)) {
            List<Path> txtFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted()
                    .toList();
            for (Path txt : txtFiles) {
                String content = Files.readString(txt, StandardCharsets.UTF_8);
                Matcher m = SUMMARY_PATTERN.matcher(content);
                if (m.find()) {
                    found = true;
                    total += Integer.parseInt(m.group(1));
                    failures += Integer.parseInt(m.group(2));
                    errors += Integer.parseInt(m.group(3));
                    skipped += Integer.parseInt(m.group(4));
                }
            }
        } catch (IOException e) {
            log.warn("读取 Surefire 报告失败: {}", reportsDir, e);
            return new SurefireSummary(false, 0, 0, 0, 0);
        }

        // failedTests = failures + errors
        int failed = failures + errors;
        int passed = total - failed - skipped;
        return new SurefireSummary(found, total, Math.max(0, passed), failed, skipped);
    }

    @Getter
    @AllArgsConstructor
    public static class SurefireSummary {
        /** 是否找到 Surefire 报告 */
        private final boolean reportFound;
        private final int totalTests;
        private final int passedTests;
        private final int failedTests;
        private final int skippedTests;
    }
}
