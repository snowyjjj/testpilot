package com.testpilot.utils;

import com.testpilot.dto.FailureFeedback;
import com.testpilot.dto.FailureFeedback.CompileErrorSource;
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
 * - COMPILE_ERROR：从 Maven 输出中截取 [ERROR] 编译错误行，并分类错误来源
 *   （测试源码 / 业务源码 / 依赖 / 插件）。只有测试源码错误才允许 LLM 修复。
 * - FAILED：从 Surefire XML 报告中提取失败用例（类、方法、异常类型、消息、堆栈）。
 * 所有内容都经过长度限制，避免将大量日志传给 LLM。
 */
public final class ExecutionFeedbackExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExecutionFeedbackExtractor.class);

    // 匹配 [ERROR] /path/File.java:[line,col] message
    private static final Pattern COMPILE_ERROR_PATTERN = Pattern.compile(
            "\\[ERROR\\]\\s+(.+?\\.java):\\[(\\d+),(\\d+)\\]\\s*(.+)", Pattern.MULTILINE);

    // 匹配 [ERROR] /path/File.java:[line] message（部分 Maven 版本格式）
    private static final Pattern COMPILE_ERROR_LINE_ONLY = Pattern.compile(
            "\\[ERROR\\]\\s+(.+?\\.java):\\[(\\d+)\\]\\s*(.+)", Pattern.MULTILINE);

    // 依赖解析失败的关键标识
    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile(
            "(Could not resolve dependencies|Non-resolvable|Failed to collect dependencies|"
                    + "Could not find artifact|Could not transfer artifact)", Pattern.CASE_INSENSITIVE);

    // 插件错误（非 compiler 插件）
    private static final Pattern PLUGIN_ERROR_PATTERN = Pattern.compile(
            "Failed to execute goal .*?:(?!maven-compiler-plugin).*?:.*?\\(.*?\\) on project");

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
     * @param result       Maven 执行结果
     * @param surefireDir  target/surefire-reports 目录路径（用于解析 XML）
     * @param testFileName TestPilot 生成的测试文件名（如 CalculatorTest.java），用于判断编译错误来源
     * @return 结构化反馈；若状态不是 COMPILE_ERROR 或 FAILED，返回 null
     */
    public static FailureFeedback extract(TestExecutionResult result, Path surefireDir, String testFileName) {
        if (result == null) {
            return null;
        }
        String status = result.getStatus();

        if ("COMPILE_ERROR".equals(status)) {
            return extractCompileFeedback(result, testFileName);
        }
        if ("FAILED".equals(status)) {
            return extractTestFailureFeedback(surefireDir, result);
        }
        return null;
    }

    /**
     * 兼容旧调用（不指定测试文件名，编译错误来源标记为 UNKNOWN）。
     */
    public static FailureFeedback extract(TestExecutionResult result, Path surefireDir) {
        return extract(result, surefireDir, null);
    }

    private static FailureFeedback extractCompileFeedback(TestExecutionResult result, String testFileName) {
        String output = result.getOutput() == null ? "" : result.getOutput();

        // 1. 先判断是否为依赖解析失败
        if (DEPENDENCY_PATTERN.matcher(output).find()) {
            String summary = "依赖解析失败，不允许自动修复";
            return new FailureFeedback(FailureType.COMPILE, CompileErrorSource.DEPENDENCY,
                    List.of(summary), List.of(), summary);
        }

        // 2. 判断是否为插件错误（非 compiler 插件）
        boolean pluginError = PLUGIN_ERROR_PATTERN.matcher(output).find();

        // 3. 提取编译错误行并分类
        List<String> errors = new ArrayList<>();
        boolean hasTestSourceError = false;
        boolean hasMainSourceError = false;
        boolean hasOtherSourceError = false;

        Matcher m = COMPILE_ERROR_PATTERN.matcher(output);
        while (m.find() && errors.size() < MAX_COMPILE_ERRORS) {
            String file = m.group(1);
            String line = m.group(2);
            String col = m.group(3);
            String msg = m.group(4);
            String fileName = extractFileName(file);
            String one = fileName + ":" + line + ":" + col + " " + msg;
            if (one.length() > MAX_COMPILE_ERROR_LENGTH) {
                one = one.substring(0, MAX_COMPILE_ERROR_LENGTH) + "...";
            }
            errors.add(one);

            CompileErrorSource src = classifySource(file, testFileName);
            if (src == CompileErrorSource.TEST_SOURCE) {
                hasTestSourceError = true;
            } else if (src == CompileErrorSource.MAIN_SOURCE) {
                hasMainSourceError = true;
            } else {
                hasOtherSourceError = true;
            }
        }

        // 如果标准格式没匹配到，尝试 line-only 格式
        if (errors.isEmpty()) {
            Matcher m2 = COMPILE_ERROR_LINE_ONLY.matcher(output);
            while (m2.find() && errors.size() < MAX_COMPILE_ERRORS) {
                String file = m2.group(1);
                String line = m2.group(2);
                String msg = m2.group(3);
                String fileName = extractFileName(file);
                String one = fileName + ":" + line + " " + msg;
                if (one.length() > MAX_COMPILE_ERROR_LENGTH) {
                    one = one.substring(0, MAX_COMPILE_ERROR_LENGTH) + "...";
                }
                errors.add(one);
                CompileErrorSource src = classifySource(file, testFileName);
                if (src == CompileErrorSource.TEST_SOURCE) {
                    hasTestSourceError = true;
                } else if (src == CompileErrorSource.MAIN_SOURCE) {
                    hasMainSourceError = true;
                } else {
                    hasOtherSourceError = true;
                }
            }
        }

        // 兜底：没匹配到任何编译错误行
        if (errors.isEmpty()) {
            for (String line : output.split("\n")) {
                if (line.contains("[ERROR]") && errors.size() < MAX_COMPILE_ERRORS) {
                    String trimmed = line.trim();
                    if (trimmed.length() > MAX_COMPILE_ERROR_LENGTH) {
                        trimmed = trimmed.substring(0, MAX_COMPILE_ERROR_LENGTH) + "...";
                    }
                    errors.add(trimmed);
                }
            }
            hasOtherSourceError = !errors.isEmpty();
        }

        // 4. 确定错误来源
        CompileErrorSource source;
        if (pluginError) {
            source = CompileErrorSource.PLUGIN;
        } else if (hasMainSourceError) {
            // 业务源码错误优先：不允许 LLM 修复
            source = CompileErrorSource.MAIN_SOURCE;
        } else if (hasTestSourceError && !hasOtherSourceError) {
            // 全部来自测试源码
            source = CompileErrorSource.TEST_SOURCE;
        } else if (hasTestSourceError) {
            // 混合了测试源码和其他未知来源，保守处理：不允许修复
            source = CompileErrorSource.UNKNOWN;
        } else if (hasOtherSourceError) {
            source = CompileErrorSource.UNKNOWN;
        } else {
            source = CompileErrorSource.UNKNOWN;
        }

        String summary = "编译失败，共 " + errors.size() + " 处错误（来源: " + source + "）";
        return new FailureFeedback(FailureType.COMPILE, source, errors, List.of(), summary);
    }

    private static String extractFileName(String filePath) {
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }

    /**
     * 根据错误文件路径判断编译错误来源。
     */
    private static CompileErrorSource classifySource(String filePath, String testFileName) {
        String normalized = filePath.replace('\\', '/');
        if (normalized.contains("/src/main/java/")) {
            return CompileErrorSource.MAIN_SOURCE;
        }
        if (normalized.contains("/src/test/java/")) {
            if (testFileName != null && normalized.endsWith("/" + testFileName)) {
                return CompileErrorSource.TEST_SOURCE;
            }
            // 其他测试源码文件（非 TestPilot 生成），保守处理
            return CompileErrorSource.UNKNOWN;
        }
        if (normalized.contains("/target/generated-sources/")) {
            return CompileErrorSource.UNKNOWN;
        }
        // 路径信息不足时，若文件名与 TestPilot 生成的测试文件名一致，判定为测试源码
        if (testFileName != null && normalized.endsWith("/" + testFileName)) {
            return CompileErrorSource.TEST_SOURCE;
        }
        return CompileErrorSource.UNKNOWN;
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
        return new FailureFeedback(FailureType.TEST, CompileErrorSource.UNKNOWN, List.of(), infos, summary);
    }
}
