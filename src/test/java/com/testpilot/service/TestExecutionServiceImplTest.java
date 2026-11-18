package com.testpilot.service;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.CoverageResult;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.service.impl.TestExecutionServiceImpl;
import com.testpilot.utils.JacocoReportParser;
import com.testpilot.utils.MavenProcessRunner;
import com.testpilot.utils.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestExecutionServiceImplTest {

    private ProjectService projectService;
    private MavenProcessRunner processRunner;
    private TestExecutionServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        processRunner = mock(MavenProcessRunner.class);
        service = new TestExecutionServiceImpl(projectService, processRunner, new com.testpilot.utils.ProjectExecutionLock());
    }

    private void writeSurefireReport(Path mavenDir, String content) throws IOException {
        Path reportsDir = mavenDir.resolve("target").resolve("surefire-reports");
        Files.createDirectories(reportsDir);
        Files.writeString(reportsDir.resolve("com.example.CalculatorTest.txt"), content, StandardCharsets.UTF_8);
    }

    /**
     * 模拟 Maven 执行：在 run() 被调用时写入 surefire 报告（模拟执行过程中生成报告），
     * 因为 executeTests 会在执行前清理旧报告。
     */
    private Answer<ProcessResult> runWithReport(ProcessResult result, String reportContent) {
        return invocation -> {
            Path mavenDir = invocation.getArgument(0);
            writeSurefireReport(mavenDir, reportContent);
            return result;
        };
    }

    @Test
    void executeTests_success_passedStatus() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(runWithReport(
                        new ProcessResult(0, "BUILD SUCCESS", "", false, 1200L),
                        "Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n"));

        TestExecutionResult result = service.executeTests("p1");

        assertTrue(result.isSuccess());
        assertEquals("PASSED", result.getStatus());
        assertEquals(0, result.getExitCode());
        assertEquals(4, result.getTotalTests());
        assertEquals(4, result.getPassedTests());
        assertEquals(0, result.getFailedTests());
        assertEquals(1200L, result.getExecutionTime());
    }

    @Test
    void executeTests_testFailure_failedStatus() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(runWithReport(
                        new ProcessResult(1, "BUILD FAILURE", "", false, 1500L),
                        "Tests run: 5, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n"));

        TestExecutionResult result = service.executeTests("p1");

        assertFalse(result.isSuccess());
        assertEquals("FAILED", result.getStatus());
        assertEquals(2, result.getFailedTests());
    }

    @Test
    void executeTests_compileError_noReports_compileErrorStatus() {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenReturn(new ProcessResult(1, "COMPILATION ERROR", "symbol not found", false, 800L));

        TestExecutionResult result = service.executeTests("p1");

        assertFalse(result.isSuccess());
        assertEquals("COMPILE_ERROR", result.getStatus());
        assertEquals(0, result.getTotalTests());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void executeTests_timeout_timeoutStatus() {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenReturn(new ProcessResult(-1, "...", "", true, 120000L));

        TestExecutionResult result = service.executeTests("p1");

        assertFalse(result.isSuccess());
        assertEquals("TIMEOUT", result.getStatus());
        assertEquals(-1, result.getExitCode());
        assertTrue(result.getErrorMessage().contains("超时"));
    }

    @Test
    void executeTests_exitZeroNoReports_noReportsStatus() {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenReturn(new ProcessResult(0, "No tests to run", "", false, 500L));

        TestExecutionResult result = service.executeTests("p1");

        // 构建成功但无报告
        assertEquals("NO_REPORTS", result.getStatus());
        assertEquals(0, result.getTotalTests());
        // NO_REPORTS 不能视为测试通过
        assertFalse(result.isSuccess(), "没有测试报告不应视为测试通过");
    }

    @Test
    void executeTests_outputTruncated_whenExceedsLimit() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        String hugeOutput = "x".repeat(100000);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(runWithReport(
                        new ProcessResult(0, hugeOutput, "", false, 100L),
                        "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n"));

        TestExecutionResult result = service.executeTests("p1");

        // 默认 max-log-length 51200，输出应被截断
        assertTrue(result.getOutput().length() <= 51200 + 100);
        assertTrue(result.getOutput().contains("truncated"));
    }

    @Test
    void executeTests_concurrentSameProject_rejected() throws Exception {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);

        // 让第一次执行阻塞，第二次被拒绝
        AtomicBoolean firstDone = new AtomicBoolean(false);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer((Answer<ProcessResult>) invocation -> {
                    // 模拟长时间执行
                    Thread.sleep(300);
                    firstDone.set(true);
                    return new ProcessResult(0, "ok", "", false, 300L);
                });

        Thread t1 = new Thread(() -> service.executeTests("p1"));
        t1.start();
        Thread.sleep(100); // 确保 t1 已进入执行

        BusinessException ex = assertThrows(BusinessException.class, () -> service.executeTests("p1"));
        assertEquals(409, ex.getCode());

        t1.join();
        assertTrue(firstDone.get());
    }

    @Test
    void executeTests_differentProjects_canRunConcurrently() throws Exception {
        Path dir1 = tempDir.resolve("p1");
        Path dir2 = tempDir.resolve("p2");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);

        when(projectService.getMavenProjectDir("p1")).thenReturn(dir1);
        when(projectService.getMavenProjectDir("p2")).thenReturn(dir2);
        when(processRunner.run(any(), any(String[].class), any(), anyInt()))
                .thenReturn(new ProcessResult(0, "ok", "", false, 50L));

        Thread t1 = new Thread(() -> service.executeTests("p1"));
        Thread t2 = new Thread(() -> service.executeTests("p2"));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        verify(processRunner, atLeastOnce()).run(eq(dir1), any(String[].class), any(), anyInt());
        verify(processRunner, atLeastOnce()).run(eq(dir2), any(String[].class), any(), anyInt());
    }

    @Test
    void executeTests_mavenStartupFailure_mavenErrorStatus() {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenReturn(new ProcessResult(-1, "", "无法启动 Maven: mvn.cmd not found", false, 10L));

        TestExecutionResult result = service.executeTests("p1");

        assertFalse(result.isSuccess());
        assertEquals("MAVEN_ERROR", result.getStatus());
    }

    @Test
    void executeTests_passesFixedTestCommand_noUserArgs() {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenReturn(new ProcessResult(0, "ok", "", false, 10L));

        service.executeTests("p1");

        // 验证只传入固定的 "test" 参数，不接受用户输入
        verify(processRunner).run(eq(tempDir), eq(new String[]{"test"}), any(), anyInt());
    }

    @Test
    void executeTests_blankProjectId_throws() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.executeTests("  "));
        assertEquals(400, ex.getCode());
        verify(processRunner, never()).run(any(), any(), any(), anyInt());
    }

    // 旧 Surefire 报告不会污染本次执行结果
    @Test
    void executeTests_staleReportsDoNotPolluteNewResult() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        // 预先写入旧报告（大量失败）
        writeSurefireReport(tempDir, "Tests run: 10, Failures: 8, Errors: 1, Skipped: 1, Time elapsed: 0.1 sec\n");

        // 本次执行只生成 4 通过的新报告
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(runWithReport(
                        new ProcessResult(0, "BUILD SUCCESS", "", false, 500L),
                        "Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n"));

        TestExecutionResult result = service.executeTests("p1");

        assertTrue(result.isSuccess());
        assertEquals("PASSED", result.getStatus());
        assertEquals(4, result.getTotalTests());
        assertEquals(4, result.getPassedTests());
        assertEquals(0, result.getFailedTests());
    }

    // Surefire 报告清理失败时，不允许继续执行 Maven
    @Test
    void executeTests_cleanupFailure_stopsExecution() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);

        Path reportsDir = tempDir.resolve("target").resolve("surefire-reports");
        Files.createDirectories(reportsDir);
        Files.writeString(reportsDir.resolve("stale-report.txt"), "Tests run: 10, Failures: 8");

        // 模拟 Files.deleteIfExists 抛出 IOException，模拟清理失败
        try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
            filesMock.when(() -> Files.deleteIfExists(any(Path.class)))
                    .thenThrow(new IOException("模拟清理失败"));

            TestExecutionResult result = service.executeTests("p1");

            // 清理失败应返回 MAVEN_ERROR，且不执行 Maven
            assertEquals("MAVEN_ERROR", result.getStatus());
            verify(processRunner, never()).run(any(), any(), any(), anyInt());
        }
    }

    // ============ 符号链接安全测试 ============

    /**
     * 检测当前环境是否支持通过 Files.isSymbolicLink 识别符号链接。
     * 在某些 Windows 环境下，createSymbolicLink 成功但 isSymbolicLink 返回 false，
     * 此时依赖 isSymbolicLink 的测试应跳过。
     */
    private boolean isSymbolicLinkDetectable(Path base) {
        Path target = base.resolve("symlink-target");
        Path link = base.resolve("symlink-link");
        try {
            Files.createDirectories(target);
            Files.createSymbolicLink(link, target);
            return Files.isSymbolicLink(link);
        } catch (IOException e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(link);
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * reportsDir 本身是符号链接时，cleanSurefireReports 应抛出 IOException，
     * doExecute 不应调用 MavenProcessRunner。
     */
    @Test
    void executeTests_reportsDirIsSymlink_stopsExecution() throws IOException {
        Assumptions.assumeTrue(isSymbolicLinkDetectable(tempDir),
                "当前环境无法通过 Files.isSymbolicLink 识别符号链接，跳过测试");

        Path realReportsDir = tempDir.resolve("target").resolve("surefire-reports-real");
        Files.createDirectories(realReportsDir);
        Files.writeString(realReportsDir.resolve("stale.txt"), "Tests run: 99, Failures: 99");

        Path reportsDir = tempDir.resolve("target").resolve("surefire-reports");
        Files.createSymbolicLink(reportsDir, realReportsDir);

        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);

        TestExecutionResult result = service.executeTests("p1");

        assertEquals("MAVEN_ERROR", result.getStatus(),
                "reportsDir 为符号链接时应返回 MAVEN_ERROR");
        verify(processRunner, never()).run(any(), any(), any(), anyInt());
    }

    /**
     * reportsDir 内部有指向外部文件的符号链接时，清理只删除链接本身，
     * 不跟随链接删除外部目标文件。
     */
    @Test
    void cleanSurefireReports_internalSymlink_deletesLinkOnly() throws IOException {
        // 尝试创建符号链接，若不支持则跳过
        Path externalFile = tempDir.resolve("external-secret.txt");
        Files.writeString(externalFile, "important external data");

        Path reportsDir = tempDir.resolve("target").resolve("surefire-reports");
        Files.createDirectories(reportsDir);
        // 同时放一个普通报告文件
        Files.writeString(reportsDir.resolve("normal-report.txt"), "Tests run: 5, Failures: 1");

        Path internalLink = reportsDir.resolve("link-to-external.txt");
        try {
            Files.createSymbolicLink(internalLink, externalFile);
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "当前系统不支持创建符号链接，跳过测试");
            return;
        }

        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenReturn(new ProcessResult(0, "ok", "", false, 50L));

        service.executeTests("p1");

        // 符号链接本身应被删除（NOFOLLOW_LINKS 确认链接本身不存在，而非仅目标不可访问）
        assertFalse(Files.exists(internalLink, LinkOption.NOFOLLOW_LINKS), "内部符号链接应被删除");
        // 外部目标文件应仍然存在
        assertTrue(Files.exists(externalFile), "符号链接指向的外部文件不应被删除");
        // 普通报告文件应被清理
        assertFalse(Files.exists(reportsDir.resolve("normal-report.txt")),
                "普通报告文件应被清理");
    }

    /**
     * 普通报告目录（非符号链接）可以正常清理，清理后 Maven 正常执行。
     */
    @Test
    void executeTests_normalReportsDir_cleanedAndExecuted() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);

        Path reportsDir = tempDir.resolve("target").resolve("surefire-reports");
        Files.createDirectories(reportsDir);
        Files.writeString(reportsDir.resolve("old-report.txt"),
                "Tests run: 10, Failures: 8, Errors: 1, Skipped: 1");

        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(runWithReport(
                        new ProcessResult(0, "BUILD SUCCESS", "", false, 500L),
                        "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n"));

        TestExecutionResult result = service.executeTests("p1");

        // 旧报告被清理，新报告生效
        assertTrue(result.isSuccess());
        assertEquals("PASSED", result.getStatus());
        assertEquals(3, result.getTotalTests());
        // 旧报告文件已被删除
        assertFalse(Files.exists(reportsDir.resolve("old-report.txt")),
                "旧报告文件应在执行前被清理");
        verify(processRunner, atLeastOnce()).run(eq(tempDir), any(String[].class), any(), anyInt());
    }

    // ====== 覆盖率执行相关测试 ======

    /**
     * 模拟 Maven 执行：在 run() 被调用时写入 surefire 报告 + jacoco.exec + jacoco.xml。
     * 模拟准备完整的覆盖率数据环境。
     */
    private Answer<ProcessResult> runWithCoverageData(
            ProcessResult testResult, ProcessResult reportResult,
            String surefireContent, String jacocoXmlContent) {
        return invocation -> {
            Path mavenDir = invocation.getArgument(0);
            String[] args = invocation.getArgument(1);

            // 每次调用都写 surefire 报告
            if (surefireContent != null) {
                writeSurefireReport(mavenDir, surefireContent);
            }

            boolean isFirstCall = args != null && args.length >= 1 && args[0].contains("prepare-agent");
            boolean isReportCall = args != null && args.length >= 1
                    && args[0].contains("jacoco-maven-plugin") && args[0].contains(":report");

            if (isFirstCall) {
                Path jacocoExec = mavenDir.resolve("target").resolve("jacoco.exec");
                Files.createDirectories(jacocoExec.getParent());
                Files.write(jacocoExec, new byte[]{1, 2, 3, 4, 5});
                return testResult;
            }

            if (isReportCall) {
                if (jacocoXmlContent != null) {
                    Path jacocoXml = mavenDir.resolve("target").resolve("site")
                            .resolve("jacoco").resolve("jacoco.xml");
                    Files.createDirectories(jacocoXml.getParent());
                    Files.writeString(jacocoXml, jacocoXmlContent, StandardCharsets.UTF_8);
                }
                return reportResult;
            }

            return testResult;  // 普通 test 调用
        };
    }

    private String validJacocoXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<report name=\"test\">\n"
                + "  <counter type=\"INSTRUCTION\" missed=\"6\" covered=\"12\"/>\n"
                + "  <counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/>\n"
                + "  <counter type=\"LINE\" missed=\"1\" covered=\"3\"/>\n"
                + "  <counter type=\"METHOD\" missed=\"1\" covered=\"3\"/>\n"
                + "  <counter type=\"CLASS\" missed=\"0\" covered=\"1\"/>\n"
                + "</report>";
    }

    @Test
    void executeTestsWithCoverage_passed_generatesCoverage() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(runWithCoverageData(
                        new ProcessResult(0, "BUILD SUCCESS", "", false, 1000L),   // testResult
                        new ProcessResult(0, "REPORT OK", "", false, 200L),          // reportResult
                        "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n",
                        validJacocoXml()));

        CoverageResult result = service.executeTestsWithCoverage("p1");

        // 测试状态正确
        assertNotNull(result.getTestExecutionResult());
        assertEquals("PASSED", result.getTestExecutionResult().getStatus());
        assertTrue(result.getTestExecutionResult().isSuccess());

        // 覆盖率状态正确
        assertEquals("COVERAGE_GENERATED", result.getCoverageStatus());
        assertNotNull(result.getMetrics());
        assertEquals(6, result.getMetrics().size());
        assertTrue(result.getMetrics().stream().anyMatch(m -> m.isAvailable()));
    }

    @Test
    void executeTestsWithCoverage_failedTest_stillGeneratesCoverage() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(runWithCoverageData(
                        new ProcessResult(1, "BUILD FAILURE", "", false, 1000L),    // testResult: 断言失败
                        new ProcessResult(0, "REPORT OK", "", false, 200L),          // reportResult
                        "Tests run: 5, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n",
                        validJacocoXml()));

        CoverageResult result = service.executeTestsWithCoverage("p1");

        assertEquals("FAILED", result.getTestExecutionResult().getStatus());
        assertFalse(result.getTestExecutionResult().isSuccess());
        // FAILED 但覆盖率仍有效
        assertEquals("COVERAGE_GENERATED", result.getCoverageStatus());
    }

    @Test
    void executeTestsWithCoverage_compileError_noCoverage() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        // compile error：process 非零退出且无 surefire 报告
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenReturn(new ProcessResult(1, "COMPILATION ERROR", "symbol not found", false, 800L));

        CoverageResult result = service.executeTestsWithCoverage("p1");

        assertEquals("COMPILE_ERROR", result.getTestExecutionResult().getStatus());
        assertEquals("NO_COVERAGE", result.getCoverageStatus());
        assertTrue(result.getMetrics().isEmpty());
        // jacoco.exec 不存在，所以不应执行 report 调用
        verify(processRunner, never()).run(eq(tempDir),
                org.mockito.ArgumentMatchers.argThat((String[] args) ->
                        args != null && args.length >= 1 && args[0].contains("jacoco:report")),
                any(), anyInt());
    }

    @Test
    void executeTestsWithCoverage_noJacocoExec_noReport() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        // 测试通过但 jacoco.exec 未生成（模拟 forkCount=0 或自定义 argLine 不含 ${argLine}）
        // runWithCoverageData 的新签名需要 4 个参数，这里直接用 thenAnswer 覆盖旧 mock
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(invocation -> {
                    Path mavenDir = invocation.getArgument(0);
                    String[] args = invocation.getArgument(1);
                    if (args != null && args.length >= 1 && args[0].contains("prepare-agent")) {
                        // 只写 surefire，不写 jacoco.exec
                        writeSurefireReport(mavenDir,
                                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n");
                    }
                    return new ProcessResult(0, "BUILD SUCCESS", "", false, 1000L);
                });

        CoverageResult result = service.executeTestsWithCoverage("p1");

        assertEquals("PASSED", result.getTestExecutionResult().getStatus());
        assertEquals("NO_REPORT", result.getCoverageStatus());
        assertNotNull(result.getCoverageError());
        assertTrue(result.getCoverageError().contains("jacoco.exec"));
    }

    @Test
    void executeTestsWithCoverage_preservesTestExecutionResult() throws IOException {
        // 覆盖率失败不应篡改 testExecutionResult
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(invocation -> {
                    Path mavenDir = invocation.getArgument(0);
                    String[] args = invocation.getArgument(1);
                    if (args != null && args.length >= 1 && args[0].contains("prepare-agent")) {
                        writeSurefireReport(mavenDir,
                                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n");
                        // 写 jacoco.exec
                        Path jacocoExec = mavenDir.resolve("target").resolve("jacoco.exec");
                        Files.createDirectories(jacocoExec.getParent());
                        Files.write(jacocoExec, new byte[]{1, 2, 3});
                    }
                    if (args != null && args.length >= 1
                            && args[0].contains("jacoco-maven-plugin") && args[0].contains(":report")) {
                        // report 执行失败
                        return new ProcessResult(1, "REPORT FAILED", "", false, 300L);
                    }
                    return new ProcessResult(0, "BUILD SUCCESS", "", false, 1000L);
                });

        CoverageResult result = service.executeTestsWithCoverage("p1");

        // 测试状态保持 PASSED
        assertEquals("PASSED", result.getTestExecutionResult().getStatus());
        // 覆盖率失败独立报告
        assertEquals("NO_REPORT", result.getCoverageStatus());
        assertNotNull(result.getCoverageError());
    }

    @Test
    void executeTestsWithCoverage_mavenError_noCoverage() {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenReturn(new ProcessResult(-1, "", "无法启动", false, 0L));

        CoverageResult result = service.executeTestsWithCoverage("p1");

        assertEquals("MAVEN_ERROR", result.getTestExecutionResult().getStatus());
        assertEquals("NO_COVERAGE", result.getCoverageStatus());
        assertTrue(result.getMetrics().isEmpty());
    }

    @Test
    void executeTestsWithCoverage_concurrentSameProject_rejected() throws Exception {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        // 第一个覆盖率调用持锁期间阻塞，第二个应被拒绝（409）
        AtomicBoolean firstDone = new AtomicBoolean(false);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer((Answer<ProcessResult>) invocation -> {
                    Thread.sleep(300);
                    firstDone.set(true);
                    return new ProcessResult(0, "ok", "", false, 300L);
                });

        Thread t1 = new Thread(() -> service.executeTestsWithCoverage("p1"));
        t1.start();
        Thread.sleep(100); // 确保 t1 已进入执行并持锁

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.executeTestsWithCoverage("p1"));
        assertEquals(409, ex.getCode());

        t1.join();
        assertTrue(firstDone.get());
    }

    @Test
    void executeTests_holdingLock_blocksCoverageExecution() throws Exception {
        // 证明普通测试执行与覆盖率执行共用同一把 ProjectExecutionLock：
        // 普通测试持锁期间，覆盖率执行应被拒绝（409）。
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        AtomicBoolean firstDone = new AtomicBoolean(false);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer((Answer<ProcessResult>) invocation -> {
                    Thread.sleep(300);
                    firstDone.set(true);
                    return new ProcessResult(0, "ok", "", false, 300L);
                });

        Thread t1 = new Thread(() -> service.executeTests("p1"));
        t1.start();
        Thread.sleep(100);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.executeTestsWithCoverage("p1"));
        assertEquals(409, ex.getCode());

        t1.join();
        assertTrue(firstDone.get());
    }

    @Test
    void executeTestsWithCoverage_holdingLock_blocksNormalExecution() throws Exception {
        // 反向证明：覆盖率执行持锁期间，普通测试执行应被拒绝（409）。
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        AtomicBoolean firstDone = new AtomicBoolean(false);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer((Answer<ProcessResult>) invocation -> {
                    Thread.sleep(300);
                    firstDone.set(true);
                    return new ProcessResult(0, "ok", "", false, 300L);
                });

        Thread t1 = new Thread(() -> service.executeTestsWithCoverage("p1"));
        t1.start();
        Thread.sleep(100);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.executeTests("p1"));
        assertEquals(409, ex.getCode());

        t1.join();
        assertTrue(firstDone.get());
    }

    @Test
    void executeTestsWithCoverage_cleansOldJacocoExecAndXml() throws IOException {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);

        // 预写旧 jacoco.exec 和 jacoco.xml
        Path jacocoExec = tempDir.resolve("target").resolve("jacoco.exec");
        Path jacocoDir = tempDir.resolve("target").resolve("site").resolve("jacoco");
        Files.createDirectories(jacocoExec.getParent());
        Files.createDirectories(jacocoDir);
        Files.write(jacocoExec, new byte[]{99, 98, 97});  // 旧数据
        Files.writeString(jacocoDir.resolve("jacoco.xml"), "old content", StandardCharsets.UTF_8);

        assertTrue(Files.exists(jacocoExec), "前置条件：旧 jacoco.exec 应存在");
        assertTrue(Files.exists(jacocoDir.resolve("jacoco.xml")), "前置条件：旧 jacoco.xml 应存在");

        // 实际执行（mock 的 Maven 会重新写 surefire）
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(inv -> {
                    Path mavenDir = inv.getArgument(0);
                    String[] args = inv.getArgument(1);
                    if (args.length >= 2 && args[0].contains("prepare-agent")) {
                        writeSurefireReport(mavenDir,
                                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 sec\n");
                    }
                    return new ProcessResult(0, "BUILD SUCCESS", "", false, 1000L);
                });

        service.executeTestsWithCoverage("p1");

        // 旧文件应被清理
        assertFalse(Files.exists(jacocoExec), "旧 jacoco.exec 应被清理");
        assertFalse(Files.exists(jacocoDir.resolve("jacoco.xml")), "旧 jacoco.xml 应被清理");
    }

    /**
     * 覆盖率流程中，清理旧 JaCoCo 文件失败时必须立即停止，不执行 Maven。
     * <p>
     * 可靠触发清理失败的方式（不使用静态 Mock）：
     * <ul>
     *   <li>Windows：以 {@code RandomAccessFile("rw")} 打开 {@code target/jacoco.exec}，
     *       Windows 文件共享语义会阻止其他句柄删除该文件，
     *       {@link Files#deleteIfExists} 抛出 {@link java.nio.file.FileSystemException}；</li>
     *   <li>非 Windows：将 {@code target/jacoco.exec} 构造为非空目录，
     *       {@link Files#deleteIfExists} 抛出 {@link java.nio.file.DirectoryNotEmptyException}。</li>
     * </ul>
     */
    @Test
    void executeTestsWithCoverage_jacocoExecCleanupFailure_stopsBeforeMaven() throws Exception {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);

        Path jacocoExec = tempDir.resolve("target").resolve("jacoco.exec");
        Files.createDirectories(jacocoExec.getParent());

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Closeable cleanupHandle;
        if (os.contains("win")) {
            // Windows：写入文件后用 RandomAccessFile 持有排他锁，阻止删除
            Files.writeString(jacocoExec, "locked");
            cleanupHandle = new RandomAccessFile(jacocoExec.toFile(), "rw");
        } else {
            // 非 Windows：构造非空目录，Files.deleteIfExists 抛 DirectoryNotEmptyException
            Files.createDirectories(jacocoExec);
            Files.writeString(jacocoExec.resolve("blocker.txt"), "cannot-delete-non-empty-dir");
            cleanupHandle = () -> { };
        }

        try {
            CoverageResult result = service.executeTestsWithCoverage("p1");

            // 清理失败应返回 MAVEN_ERROR，且绝不调用 Maven
            assertNotNull(result.getTestExecutionResult());
            assertEquals("MAVEN_ERROR", result.getTestExecutionResult().getStatus(),
                    "JaCoCo 清理失败时测试执行结果应为 MAVEN_ERROR");
            assertEquals("NO_COVERAGE", result.getCoverageStatus(),
                    "清理失败时覆盖率状态应为 NO_COVERAGE");
            verify(processRunner, never()).run(any(), any(String[].class), any(), anyInt());
        } finally {
            cleanupHandle.close();
        }
    }
}
