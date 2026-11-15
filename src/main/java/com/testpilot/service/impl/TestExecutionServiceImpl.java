package com.testpilot.service.impl;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.service.ProjectService;
import com.testpilot.service.TestExecutionService;
import com.testpilot.utils.MavenProcessRunner;
import com.testpilot.utils.ProcessResult;
import com.testpilot.utils.SurefireReportParser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 测试执行服务实现。
 * <p>
 * 安全说明：本服务直接在宿主机执行用户上传项目的 Maven 命令，
 * 仅适用于本地毕业设计 Demo。运行不可信项目需进一步使用容器隔离和资源限制。
 */
@Service
@RequiredArgsConstructor
public class TestExecutionServiceImpl implements TestExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TestExecutionServiceImpl.class);

    private final ProjectService projectService;
    private final MavenProcessRunner mavenProcessRunner;

    /** 正在执行的项目集合，用于防止同一项目并发执行 */
    private final Set<String> runningProjects = ConcurrentHashMap.newKeySet();

    @Value("${testpilot.maven.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${testpilot.maven.max-log-length:51200}")
    private int maxLogLength;

    @Override
    public TestExecutionResult executeTests(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(400, "项目 ID 不能为空");
        }

        // 并发控制：同一项目不允许同时执行
        if (!runningProjects.add(projectId)) {
            throw new BusinessException(409, "该项目正在执行测试，请稍后重试");
        }

        try {
            return doExecute(projectId);
        } finally {
            runningProjects.remove(projectId);
        }
    }

    private TestExecutionResult doExecute(String projectId) {
        // 1. 定位 Maven 项目根目录（含 pom.xml）
        Path mavenDir = projectService.getMavenProjectDir(projectId);

        // 1a. 清理旧的 Surefire 报告，避免上次执行结果污染本次
        Path reportsDir = mavenDir.resolve("target").resolve("surefire-reports");
        try {
            cleanSurefireReports(reportsDir, mavenDir);
        } catch (IOException e) {
            log.error("清理 Surefire 报告失败，停止执行以避免旧报告污染: {}", reportsDir, e);
            return new TestExecutionResult(
                    projectId, false, "MAVEN_ERROR", -1, 0, 0, 0, 0, 0, "",
                    "清理 Surefire 报告失败，无法确保报告准确性，已停止执行: " + e.getMessage());
        }

        // 2. 执行固定的 Maven test 命令，不接受用户参数
        ProcessResult processResult = mavenProcessRunner.run(
                mavenDir,
                new String[]{"test"},
                Duration.ofSeconds(timeoutSeconds),
                maxLogLength);

        // 3. 解析 Surefire 报告
        SurefireReportParser.SurefireSummary summary = SurefireReportParser.parse(reportsDir);

        // 4. 综合判断状态
        String status;
        boolean success;
        String errorMessage = "";

        if (processResult.isTimedOut()) {
            status = "TIMEOUT";
            success = false;
            errorMessage = "Maven 执行超时（" + timeoutSeconds + " 秒），已强制终止";
        } else if (processResult.getExitCode() == -1) {
            // 退出码 -1 表示进程未能正常启动（如 Maven 未安装）
            status = "MAVEN_ERROR";
            success = false;
            errorMessage = "Maven 启动失败：" + (processResult.getStderr().isBlank()
                    ? "未知错误" : processResult.getStderr());
        } else if (processResult.getExitCode() != 0) {
            // 非零退出：区分编译失败 / 测试失败 / Maven 错误
            if (!summary.isReportFound()) {
                status = "COMPILE_ERROR";
                success = false;
                errorMessage = "Maven 构建失败，未生成 Surefire 报告，可能是主源码或测试源码编译失败";
            } else if (summary.getFailedTests() > 0) {
                status = "FAILED";
                success = false;
                errorMessage = "存在测试断言失败或错误";
            } else {
                status = "MAVEN_ERROR";
                success = false;
                errorMessage = "Maven 执行失败（退出码 " + processResult.getExitCode() + "）";
            }
        } else {
            // 退出码为 0
            if (!summary.isReportFound()) {
                status = "NO_REPORTS";
                success = false; // 构建成功但无测试报告，不能视为测试通过
                errorMessage = "Maven 构建成功，但未找到 Surefire 报告，可能没有可执行的测试";
            } else if (summary.getFailedTests() > 0) {
                // 理论上退出码 0 时失败数应为 0，防御性处理
                status = "FAILED";
                success = false;
                errorMessage = "存在测试断言失败或错误";
            } else {
                status = "PASSED";
                success = true;
            }
        }

        // 合并 stdout + stderr 作为输出
        String output = processResult.getStdout();
        if (processResult.getStderr() != null && !processResult.getStderr().isBlank()) {
            output = output + "\n[stderr]\n" + processResult.getStderr();
        }
        // 再次确保不超过上限
        if (output.length() > maxLogLength) {
            output = output.substring(0, maxLogLength) + "\n... (output truncated)";
        }

        return new TestExecutionResult(
                projectId,
                success,
                status,
                processResult.getExitCode(),
                summary.getTotalTests(),
                summary.getPassedTests(),
                summary.getFailedTests(),
                summary.getSkippedTests(),
                processResult.getExecutionTimeMs(),
                output,
                errorMessage);
    }

    /**
     * 清理 Surefire 报告目录，避免旧报告污染本次执行结果。
     * <p>
     * 安全约束：只删除 reportsDir 下的内容，且所有路径必须在 mavenDir 内。
     * 不跟随符号链接（不删除符号链接指向的外部文件）。
     * 若清理过程中任一文件删除失败，抛出 IOException 由调用方决定是否继续。
     *
     * @throws IOException 遍历目录或删除文件失败时抛出
     */
    private void cleanSurefireReports(Path reportsDir, Path mavenDir) throws IOException {
        if (!Files.isDirectory(reportsDir)) {
            return;
        }
        Path normalizedMaven = mavenDir.normalize();
        try (Stream<Path> stream = Files.walk(reportsDir)) {
            // 先删除文件，再删除目录（逆序）
            List<Path> paths = stream
                    .filter(p -> p.normalize().startsWith(normalizedMaven))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path p : paths) {
                // 不删除符号链接指向的目标，只删除链接本身；跳过 reportsDir 自身
                if (Files.isSymbolicLink(p) || p.equals(reportsDir)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("清理 Surefire 报告文件失败: {}", p, e);
                    throw e;
                }
            }
        }
    }
}
