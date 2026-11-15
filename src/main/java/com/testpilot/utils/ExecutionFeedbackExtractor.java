package com.testpilot.utils;

import com.testpilot.dto.FailureFeedback;
import com.testpilot.dto.FailureFeedback.FailedCaseInfo;
import com.testpilot.dto.FailureFeedback.FailureType;
import com.testpilot.dto.TestExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Maven 执行结果中提取有价值的失败反馈信息，用于 LLM 修复。
 * <p>
 * - COMPILE_ERROR：从 Maven 输出中截取 [ERROR] 编译错误行（含文件、行号、错误描述）。
 * - FAILED：从 Surefire XML 报告中提取失败用例（类、方法、异常类型、消息、堆栈）。
 * 所有内容都经过长度限制，避免将大量日志传给 LLM。
 */
public final class ExecutionFeedbackExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExecutionFeedbackExtractor.class);

    // 匹配 [ERROR] /path/File.java:[line,col] message
    private static final Pattern COMPILE_ERROR_PATTERN = Pattern.compile(
            "\\[ERROR\\]\\s+(.+?\\.java):\\[(\\d+),(\\d+)\\]\\s*(.+)", Pattern.MULTILINE);

    // 最多保留的编译错误行数
    private static final int MAX_COMPILE_ERRORS = 20;
    // 每条编译错误最大长度
    private static final int MAX_COMPILE_ERROR_LENGTH = 300;
    // 每条失败堆栈最大长度
    private static final int MAX_STACK_LENGTH = 1500;
    // 最多保留的失败用例数
    private static final int MAX_FAILED_CASES = 10;

    private ExecutionFeedbackExtractor() {
    }

    /**
     * 根据执行结果提取失败反馈。
     *
     * @param result      Maven 执行结果
     * @param surefireDir target/surefire-reports 目录路径（用于解析 XML）
     * @return 结构化反馈；若状态不是 COMPILE_ERROR 或 FAILED，返回 null
     */
    public static FailureFeedback extract(TestExecutionResult result, Path surefireDir) {
        if (result == null) {
            return null;
        }
        String status = result.getStatus();

        if ("COMPILE_ERROR".equals(status)) {
            return extractCompileFeedback(result);
        }
        if ("FAILED".equals(status)) {
            return extractTestFailureFeedback(surefireDir, result);
        }
        return null;
    }

    private static FailureFeedback extractCompileFeedback(TestExecutionResult result) {
        List<String> errors = new ArrayList<>();
        String output = result.getOutput() == null ? "" : result.getOutput();
        Matcher m = COMPILE_ERROR_PATTERN.matcher(output);
        while (m.find() && errors.size() < MAX_COMPILE_ERRORS) {
            String file = m.group(1);
            // 只保留文件名，避免暴露完整路径
            int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
            String fileName = slash >= 0 ? file.substring(slash + 1) : file;
            String line = m.group(2);
            String col = m.group(3);
            String msg = m.group(4);
            String one = fileName + ":" + line + ":" + col + " " + msg;
            if (one.length() > MAX_COMPILE_ERROR_LENGTH) {
                one = one.substring(0, MAX_COMPILE_ERROR_LENGTH) + "...";
            }
            errors.add(one);
        }
        if (errors.isEmpty()) {
            // 没匹配到标准格式，兜底截取包含 ERROR 的前若干行
            for (String line : output.split("\n")) {
                if (line.contains("[ERROR]") && errors.size() < MAX_COMPILE_ERRORS) {
                    String trimmed = line.trim();
                    if (trimmed.length() > MAX_COMPILE_ERROR_LENGTH) {
                        trimmed = trimmed.substring(0, MAX_COMPILE_ERROR_LENGTH) + "...";
                    }
                    errors.add(trimmed);
                }
            }
        }
        String summary = "编译失败，共 " + errors.size() + " 处错误";
        return new FailureFeedback(FailureType.COMPILE, errors, List.of(), summary);
    }

    private static FailureFeedback extractTestFailureFeedback(Path surefireDir, TestExecutionResult result) {
        List<SurefireReportParser.FailedCase> failedCases =
                SurefireReportParser.parseXmlFailures(surefireDir, MAX_STACK_LENGTH);

        List<FailedCaseInfo> infos = new ArrayList<>();
        for (SurefireReportParser.FailedCase fc : failedCases) {
            if (infos.size() >= MAX_FAILED_CASES) {
                break;
            }
            infos.add(new FailedCaseInfo(
                    fc.getClassName(),
                    fc.getMethodName(),
                    fc.getExceptionType(),
                    fc.getMessage(),
                    fc.getStackTrace()));
        }

        int failedCount = result.getFailedTests();
        String summary = "测试执行失败，共 " + failedCount + " 个测试失败（解析到 "
                + infos.size() + " 个失败用例详情）";
        return new FailureFeedback(FailureType.TEST, List.of(), infos, summary);
    }
}
