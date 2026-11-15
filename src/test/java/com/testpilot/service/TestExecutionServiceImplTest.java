package com.testpilot.service;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.service.impl.TestExecutionServiceImpl;
import com.testpilot.utils.MavenProcessRunner;
import com.testpilot.utils.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        service = new TestExecutionServiceImpl(projectService, processRunner);
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
}
