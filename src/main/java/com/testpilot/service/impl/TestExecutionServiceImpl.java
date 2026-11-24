package com.testpilot.service.impl;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.CoverageMetric;
import com.testpilot.dto.CoverageResult;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.service.ProjectService;
import com.testpilot.service.TestExecutionService;
import com.testpilot.utils.JacocoReportParser;
import com.testpilot.utils.MavenProcessRunner;
import com.testpilot.utils.ProcessResult;
import com.testpilot.utils.ProjectExecutionLock;
import com.testpilot.utils.SurefireReportParser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 测试执行服务实现。
 * <p>
 * 提供普通 Maven test 执行和 JaCoCo 覆盖率收集两种模式。
 * 使用共享 {@link ProjectExecutionLock} 保证：
 * <ul>
 *   <li>同一项目的普通测试执行、覆盖率执行、测试优化流程互斥；</li>
 *   <li>优化流程全程持锁，内部 executeTests 调用可同线程重入。</li>
 * </ul>
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
    private final ProjectExecutionLock projectExecutionLock;

    @Value("${testpilot.maven.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${testpilot.maven.max-log-length:51200}")
    private int maxLogLength;

    @Value("${testpilot.jacoco.version:0.8.11}")
    private String jacocoVersion;

    // ====== 普通测试执行 ======

    @Override
    public TestExecutionResult executeTests(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(400, "项目 ID 不能为空");
        }

        if (!projectExecutionLock.tryLock(projectId)) {
            throw new BusinessException(409, "该项目正在执行测试，请稍后重试");
        }

        try {
            return doExecute(projectId);
        } finally {
            projectExecutionLock.unlock(projectId);
        }
    }

    // ====== 覆盖率执行（JaCoCo）======

    @Override
    public CoverageResult executeTestsWithCoverage(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(400, "项目 ID 不能为空");
        }

        if (!projectExecutionLock.tryLock(projectId)) {
            throw new BusinessException(409, "该项目正在执行测试，请稍后重试");
        }

        try {
            return doExecuteWithCoverage(projectId);
        } finally {
            projectExecutionLock.unlock(projectId);
        }
    }

    /**
     * JaCoCo 双 Maven 调用完整流程（全部在锁内完成）：
     * cleanSurefire + cleanJacoco → prepare-agent test → parse surefire →
     * （若 PASSED/FAILED）check exec + jacoco:report → parse jacoco.xml →
     * 返回 CoverageResult。
     */
    private CoverageResult doExecuteWithCoverage(String projectId) {
        Path mavenDir = projectService.getMavenProjectDir(projectId);

        // 清理旧 Surefire 报告
        Path reportsDir = mavenDir.resolve("target").resolve("surefire-reports");
        try {
            cleanSurefireReports(reportsDir, mavenDir);
        } catch (IOException e) {
            log.error("清理 Surefire 报告失败", e);
            TestExecutionResult failResult = buildMavenErrorResult(
                    projectId, -1, "清理 Surefire 报告失败: " + e.getMessage());
            return new CoverageResult(projectId, failResult, "NO_COVERAGE", new ArrayList<>(),
                    "清理 Surefire 报告失败: " + e.getMessage());
        }

        // 清理旧 JaCoCo 文件
        try {
            cleanJacocoReports(mavenDir);
        } catch (IOException e) {
            log.error("清理 JaCoCo 报告失败", e);
            TestExecutionResult failResult = buildMavenErrorResult(
                    projectId, -1, "清理 JaCoCo 报告失败: " + e.getMessage());
            return new CoverageResult(projectId, failResult, "NO_COVERAGE", new ArrayList<>(),
                    "清理 JaCoCo 报告失败: " + e.getMessage());
        }

        // ===== 第一次 Maven 调用：prepare-agent + test =====
        ProcessResult processResult = mavenProcessRunner.run(
                mavenDir,
                new String[]{
                        "org.jacoco:jacoco-maven-plugin:" + jacocoVersion + ":prepare-agent",
                        "test"
                },
                Duration.ofSeconds(timeoutSeconds),
                maxLogLength);

        SurefireReportParser.SurefireSummary summary = SurefireReportParser.parse(reportsDir);
        TestExecutionResult testResult = buildTestResult(projectId, processResult, summary);

        // ===== 判断测试状态并决定是否执行 report =====
        String coverageStatus = "NO_COVERAGE";
        List<CoverageMetric> metrics = new ArrayList<>();
        String coverageError = null;

        String testStatus = testResult.getStatus();
        if ("PASSED".equals(testStatus) || "FAILED".equals(testStatus)) {
            // 测试执行过，尝试生成覆盖率
            Path jacocoExec = mavenDir.resolve("target").resolve("jacoco.exec");

            // 逐级检查 jacoco.exec 路径链符号链接
            try {
                assertNoSymlinkInPathChain(jacocoExec, mavenDir);
            } catch (IOException e) {
                coverageError = "jacoco.exec 路径链含符号链接: " + e.getMessage();
                // 直接返回 NO_REPORT，不尝试 report
                return new CoverageResult(projectId, testResult, "NO_REPORT", new ArrayList<>(), coverageError);
            }

            boolean execExists = Files.exists(jacocoExec, LinkOption.NOFOLLOW_LINKS);
            boolean execIsSymlink = Files.isSymbolicLink(jacocoExec);
            long execSize = 0;
            if (execExists && !execIsSymlink) {
                try {
                    execSize = Files.size(jacocoExec);
                } catch (IOException ignored) {
                }
            }

            if (execExists && !execIsSymlink && execSize > 0) {
                // ===== 第二次 Maven 调用：jacoco:report =====
                ProcessResult reportResult = mavenProcessRunner.run(
                        mavenDir,
                        new String[]{"org.jacoco:jacoco-maven-plugin:" + jacocoVersion + ":report"},
                        Duration.ofSeconds(timeoutSeconds),
                        maxLogLength);

                if (reportResult.getExitCode() == 0) {
                    Path jacocoXml = mavenDir.resolve("target").resolve("site").resolve("jacoco").resolve("jacoco.xml");

                    // 检查 jacoco.xml 路径链
                    try {
                        assertNoSymlinkInPathChain(jacocoXml, mavenDir);
                    } catch (IOException e) {
                        coverageError = "jacoco.xml 路径链含符号链接: " + e.getMessage();
                        return new CoverageResult(projectId, testResult, "NO_REPORT", new ArrayList<>(), coverageError);
                    }

                    boolean xmlExists = Files.exists(jacocoXml, LinkOption.NOFOLLOW_LINKS);
                    boolean xmlIsSymlink = Files.isSymbolicLink(jacocoXml);

                    if (xmlExists && !xmlIsSymlink) {
                        metrics = JacocoReportParser.parse(jacocoXml);
                        if (JacocoReportParser.hasValidCounters(metrics)) {
                            coverageStatus = "COVERAGE_GENERATED";
                        } else {
                            coverageStatus = "NO_REPORT";
                            coverageError = "jacoco.xml 无有效覆盖率计数器";
                        }
                    } else {
                        coverageStatus = "NO_REPORT";
                        coverageError = "jacoco.xml 未生成或为符号链接";
                    }
                } else {
                    coverageStatus = "NO_REPORT";
                    coverageError = "JaCoCo report 执行失败（退出码 " + reportResult.getExitCode() + "）";
                }
            } else {
                coverageStatus = "NO_REPORT";
                coverageError = "jacoco.exec 未生成或为空，可能原因：pom.xml 中 Surefire 自定义了 argLine 不含 ${argLine}、forkCount=0 等";
            }
        }
        // COMPILE_ERROR / MAVEN_ERROR / TIMEOUT / NO_REPORTS → coverageStatus 保持 NO_COVERAGE

        return new CoverageResult(projectId, testResult, coverageStatus, metrics, coverageError);
    }

    // ====== 内部辅助方法 ======

    private TestExecutionResult doExecute(String projectId) {
        Path mavenDir = projectService.getMavenProjectDir(projectId);

        Path reportsDir = mavenDir.resolve("target").resolve("surefire-reports");
        try {
            cleanSurefireReports(reportsDir, mavenDir);
        } catch (IOException e) {
            log.error("清理 Surefire 报告失败，停止执行以避免旧报告污染", e);
            return buildMavenErrorResult(projectId, -1,
                    "清理 Surefire 报告失败，无法确保报告准确性，已停止执行: " + e.getMessage());
        }

        ProcessResult processResult = mavenProcessRunner.run(
                mavenDir,
                new String[]{"test"},
                Duration.ofSeconds(timeoutSeconds),
                maxLogLength);

        SurefireReportParser.SurefireSummary summary = SurefireReportParser.parse(reportsDir);

        return buildTestResult(projectId, processResult, summary);
    }

    /**
     * 综合判定测试执行状态。
     */
    private TestExecutionResult buildTestResult(String projectId, ProcessResult processResult,
                                                SurefireReportParser.SurefireSummary summary) {
        String status;
        boolean success;
        String errorMessage = "";

        if (!processResult.isTimedOut() && processResult.isOutputLimitExceeded()
                && processResult.getExitCode() != -1) {
            log.warn("Maven 进程已自然退出，但输出超过磁盘上限，保留真实退出码: {}",
                    processResult.getExitCode());
        }

        if (processResult.isTimedOut()) {
            status = "TIMEOUT";
            success = false;
            errorMessage = "Maven 执行超时（" + timeoutSeconds + " 秒），已强制终止";
        } else if (processResult.isOutputLimitExceeded() && processResult.getExitCode() == -1) {
            status = "MAVEN_ERROR";
            success = false;
            errorMessage = "Maven 输出超过磁盘上限，已终止执行";
        } else if (processResult.getExitCode() == -1) {
            status = "MAVEN_ERROR";
            success = false;
            errorMessage = "Maven 启动失败：" + (processResult.getStderr().isBlank()
                    ? "未知错误" : processResult.getStderr());
        } else if (processResult.getExitCode() != 0) {
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
            if (!summary.isReportFound()) {
                status = "NO_REPORTS";
                success = false;
                errorMessage = "Maven 构建成功，但未找到 Surefire 报告，可能没有可执行的测试";
            } else if (summary.getFailedTests() > 0) {
                status = "FAILED";
                success = false;
                errorMessage = "存在测试断言失败或错误";
            } else {
                status = "PASSED";
                success = true;
            }
        }

        String output = processResult.getStdout();
        if (processResult.getStderr() != null && !processResult.getStderr().isBlank()) {
            output = output + "\n[stderr]\n" + processResult.getStderr();
        }
        if (output.length() > maxLogLength) {
            output = output.substring(0, maxLogLength) + "\n... (output truncated)";
        }

        return new TestExecutionResult(
                projectId, success, status, processResult.getExitCode(),
                summary.getTotalTests(), summary.getPassedTests(),
                summary.getFailedTests(), summary.getSkippedTests(),
                processResult.getExecutionTimeMs(), output, errorMessage);
    }

    private TestExecutionResult buildMavenErrorResult(String projectId, int exitCode, String errorMessage) {
        return new TestExecutionResult(
                projectId, false, "MAVEN_ERROR", exitCode, 0, 0, 0, 0, 0, "", errorMessage);
    }

    // ====== 文件清理与路径安全 ======

    /**
     * 检查从 target 到 projectDir 的路径链中是否存在符号链接。
     * 逐级向上检查 target 本身及其每一级父目录（直到 projectDir，不含 projectDir）。
     * 任一级为符号链接时抛出 IOException。
     * <p>
     * 设计原则：
     * <ul>
     *   <li>先做符号链接检查，再做 normalize().startsWith() 边界检查；</li>
     *   <li>Files.isSymbolicLink 不跟随被检查的链接本身；</li>
     *   <li>normalize().startsWith() 仅作边界兜底，不替代符号链接检查。</li>
     * </ul>
     */
    private void assertNoSymlinkInPathChain(Path target, Path projectDir) throws IOException {
        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        Path current = target.toAbsolutePath().normalize();

        // 边界检查
        if (!current.startsWith(normalizedProject)) {
            throw new IOException("路径越界: " + target);
        }

        // 检查 target 本身
        if (Files.isSymbolicLink(current)) {
            throw new IOException("路径是符号链接，存在安全风险: " + current);
        }

        // 逐级检查父目录，直到 projectDir
        Path parent = current.getParent();
        while (parent != null && !parent.equals(normalizedProject) && parent.startsWith(normalizedProject)) {
            if (Files.isSymbolicLink(parent)) {
                throw new IOException("路径父目录是符号链接，存在安全风险: " + parent);
            }
            parent = parent.getParent();
        }
    }

    /**
     * 清理 Surefire 报告目录。
     * 在原有基础上新增逐级路径链符号链接检查。
     */
    private void cleanSurefireReports(Path reportsDir, Path mavenDir) throws IOException {
        // 逐级检查路径链（含 target、surefire-reports 及中间级）
        assertNoSymlinkInPathChain(reportsDir, mavenDir);

        // 目录不存在时正常返回
        if (!Files.isDirectory(reportsDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path normalizedMaven = mavenDir.toAbsolutePath().normalize();
        try (Stream<Path> stream = Files.walk(reportsDir)) {
            List<Path> paths = stream
                    .filter(p -> p.toAbsolutePath().normalize().startsWith(normalizedMaven))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path p : paths) {
                if (p.equals(reportsDir)) {
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

    /**
     * 清理旧的 JaCoCo 覆盖率文件（target/jacoco.exec + target/site/jacoco/）。
     * 统一采用逐级路径链符号链接检查，发现符号链接立即拒绝，不尝试删除链接。
     */
    private void cleanJacocoReports(Path mavenDir) throws IOException {
        Path target = mavenDir.resolve("target");

        // 1. 清理 jacoco.exec
        Path jacocoExec = target.resolve("jacoco.exec");
        assertNoSymlinkInPathChain(jacocoExec, mavenDir);
        if (Files.exists(jacocoExec, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(jacocoExec);
        }

        // 2. 清理 jacoco 报告目录 target/site/jacoco
        Path jacocoDir = target.resolve("site").resolve("jacoco");
        assertNoSymlinkInPathChain(jacocoDir, mavenDir);
        if (!Files.exists(jacocoDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(jacocoDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(jacocoDir);
            return;
        }
        Path normalizedMaven = mavenDir.toAbsolutePath().normalize();
        try (Stream<Path> stream = Files.walk(jacocoDir)) {
            List<Path> paths = stream
                    .filter(p -> p.toAbsolutePath().normalize().startsWith(normalizedMaven))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path p : paths) {
                if (p.equals(jacocoDir)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("清理 JaCoCo 报告文件失败: {}", p, e);
                    throw e;
                }
            }
        }
    }
}
