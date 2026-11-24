package com.testpilot.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultMavenProcessRunner} 基础进程执行测试。
 * <p>
 * 通过临时目录中的假 Maven 脚本启动嵌套的
 * {@link FakeMavenProcessSupport.FakeMavenMain}，
 * 不依赖真实 Maven、不启动 Spring 容器、不访问网络。
 * 本轮只覆盖命令构造、正常执行、输出截断和启动失败；
 * 超时、进程树清理和线程中断留给阶段 9.2B。
 * <p>
 * 本类所有进程测试都通过 {@link FakeMavenProcessSupport#isWindows()} 决定脚本类型，
 * 因此统一持有系统属性资源锁，避免与 os.name 修改测试并行执行。
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DefaultMavenProcessRunnerTest {

    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PROCESS_EXIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_LOG_LENGTH = 10_000;
    private static final long DEFAULT_MAX_OUTPUT_BYTES = 10L * 1024 * 1024;
    private static final String TRUNCATION_MARKER = "\n... (output truncated)";

    @TempDir
    Path tempDir;

    private DefaultMavenProcessRunner runner;
    private Path workDir;
    private final FakeMavenProcessSupport.ProcessRegistry processRegistry =
            new FakeMavenProcessSupport.ProcessRegistry();

    @BeforeEach
    void setUp() throws IOException {
        runner = new DefaultMavenProcessRunner();
        workDir = Files.createDirectories(tempDir.resolve("work"));
    }

    @AfterEach
    void tearDown() {
        // 输出超限测试会主动终止辅助进程；这里再做一次有界兜底，避免测试失败时遗留进程。
        processRegistry.trackRecords(tempDir);
        processRegistry.terminateAll();
    }

    /**
     * 生成当前平台的假 Maven，复用共享支持类中的脚本和辅助进程入口。
     */
    private Path createFakeMaven() throws Exception {
        return FakeMavenProcessSupport.createFakeMaven(tempDir);
    }

    private void useExecutable(Path executable) {
        useExecutable(runner, executable);
    }

    private void useExecutable(DefaultMavenProcessRunner target, Path executable) {
        ReflectionTestUtils.setField(target, "configuredExecutable",
                executable.toAbsolutePath().toString());
    }

    private static void setMaxOutputBytes(DefaultMavenProcessRunner target, long maxOutputBytes) {
        ReflectionTestUtils.setField(target, "maxOutputBytes", maxOutputBytes);
    }

    private FakeMavenProcessSupport.ProcessRecord awaitHelperRecord(String name) throws Exception {
        FakeMavenProcessSupport.ProcessRecord record = FakeMavenProcessSupport.awaitRecord(
                tempDir, name, FakeMavenProcessSupport.READY_TIMEOUT);
        processRegistry.track(record);
        return record;
    }

    private String readStream(byte[] content, int maxLogLength) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            return ReflectionTestUtils.invokeMethod(runner, "readStream", in, maxLogLength);
        } catch (IOException e) {
            throw new AssertionError("关闭内存输入流失败", e);
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String invokeResolveExecutable() {
        return ReflectionTestUtils.invokeMethod(runner, "resolveExecutable");
    }

    private String readRecord(String name) throws IOException {
        String value = Files.readString(tempDir.resolve(name), StandardCharsets.UTF_8).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void restoreOsName(String original) {
        if (original == null) {
            System.clearProperty("os.name");
        } else {
            System.setProperty("os.name", original);
        }
    }

    // ============================== A. 命令构造 ==============================

    @Test
    void run_configuredExecutable_prependsBAndPreservesArgumentOrder() throws Exception {
        Path fakeMaven = createFakeMaven();
        useExecutable(fakeMaven);

        ProcessResult result = runner.run(workDir,
                new String[]{"args", "test", "-DskipITs", "verify"},
                RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(0, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertEquals(List.of("-B", "args", "test", "-DskipITs", "verify"),
                Files.readAllLines(tempDir.resolve("args.txt"), StandardCharsets.UTF_8),
                "Runner 必须在用户参数前追加 -B，并保持参数原顺序");
        assertEquals(fakeMaven.toRealPath(),
                Path.of(readRecord("script-path.txt")).toRealPath(),
                "必须执行 configuredExecutable 指定的脚本");
    }

    @Test
    void run_workingDirectory_isPassedToChildProcess() throws Exception {
        Path fakeMaven = createFakeMaven();
        useExecutable(fakeMaven);

        ProcessResult result = runner.run(workDir,
                new String[]{"cwd"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(0, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertEquals(workDir.toRealPath(),
                Path.of(readRecord("cwd.txt")).toRealPath(),
                "子进程工作目录必须等于 ProcessBuilder.directory 传入的目录");
    }

    @Test
    void resolveExecutable_configuredValue_isReturnedVerbatim() {
        String configured = "C:\\tools\\mvn.cmd";
        ReflectionTestUtils.setField(runner, "configuredExecutable", configured);

        assertEquals(configured, invokeResolveExecutable());
    }

    @Test
    void resolveExecutable_blankOrNull_fallsBackToPlatformDefault() {
        String expected = isWindows() ? "mvn.cmd" : "mvn";

        for (String configured : new String[]{null, "", "   "}) {
            ReflectionTestUtils.setField(runner, "configuredExecutable", configured);
            assertEquals(expected, invokeResolveExecutable(),
                    "空配置应回退到当前平台默认值: " + configured);
        }
    }

    @Test
    void resolveExecutable_osNameWindows_returnsMvnCmd() {
        String original = System.getProperty("os.name");
        try {
            ReflectionTestUtils.setField(runner, "configuredExecutable", null);
            System.setProperty("os.name", "Windows 11");
            assertEquals("mvn.cmd", invokeResolveExecutable());
        } finally {
            restoreOsName(original);
        }
    }

    @Test
    void resolveExecutable_osNameNonWindows_returnsMvn() {
        String original = System.getProperty("os.name");
        try {
            ReflectionTestUtils.setField(runner, "configuredExecutable", null);
            System.setProperty("os.name", "Linux");
            assertEquals("mvn", invokeResolveExecutable());
        } finally {
            restoreOsName(original);
        }
    }

    // ============================== B. 正常执行 ==============================

    @Test
    void run_normalExit_returnsExitCodeAndSeparateStreams() throws Exception {
        Path fakeMaven = createFakeMaven();
        useExecutable(fakeMaven);

        ProcessResult result = runner.run(workDir,
                new String[]{"output"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(0, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertEquals("STDOUT-LINE\n", result.getStdout());
        assertEquals("STDERR-LINE\n", result.getStderr());
        assertTrue(result.getExecutionTimeMs() >= 0);
    }

    @Test
    void run_nonZeroExit_returnsActualExitCode() throws Exception {
        Path fakeMaven = createFakeMaven();
        useExecutable(fakeMaven);

        ProcessResult result = runner.run(workDir,
                new String[]{"nonzero"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertEquals("", result.getStdout());
        assertEquals("", result.getStderr());
    }

    @Test
    void run_emptyOutput_returnsEmptyStrings() throws Exception {
        Path fakeMaven = createFakeMaven();
        useExecutable(fakeMaven);

        ProcessResult result = runner.run(workDir,
                new String[]{"empty"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(0, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertEquals("", result.getStdout());
        assertEquals("", result.getStderr());
    }

    @Test
    void run_outputAtExactMaxLogLength_isNotTruncated() throws Exception {
        Path fakeMaven = createFakeMaven();
        useExecutable(fakeMaven);

        int maxLogLength = 10;
        ProcessResult result = runner.run(workDir,
                new String[]{"exact", String.valueOf(maxLogLength)},
                RUN_TIMEOUT, maxLogLength);

        String expected = "X".repeat(maxLogLength - 1) + "\n";
        assertEquals(expected, result.getStdout());
        assertEquals(maxLogLength, result.getStdout().length());
        assertFalse(result.getStdout().contains("output truncated"));
        assertEquals("", result.getStderr());
    }

    @Test
    void run_outputOverMaxLogLength_appendsTruncationMarker() throws Exception {
        Path fakeMaven = createFakeMaven();
        useExecutable(fakeMaven);

        int maxLogLength = 10;
        ProcessResult result = runner.run(workDir,
                new String[]{"truncate", String.valueOf(maxLogLength)},
                RUN_TIMEOUT, maxLogLength);

        String expected = "X".repeat(maxLogLength) + "\n... (output truncated)";
        assertEquals(expected, result.getStdout());
        assertTrue(result.getStdout().length() > maxLogLength,
                "当前实现会在达到上限后追加截断标记，最终长度可超过 maxLogLength");
        assertEquals("", result.getStderr());
    }

    @Test
    void run_stdoutAndStderr_areLimitedIndependently() throws Exception {
        Path fakeMaven = createFakeMaven();
        useExecutable(fakeMaven);

        int maxLogLength = 10;
        ProcessResult result = runner.run(workDir,
                new String[]{"truncate-both", String.valueOf(maxLogLength)},
                RUN_TIMEOUT, maxLogLength);

        String expectedStdout = "X".repeat(maxLogLength) + "\n... (output truncated)";
        String expectedStderr = "Y".repeat(maxLogLength) + "\n... (output truncated)";
        assertEquals(expectedStdout, result.getStdout());
        assertEquals(expectedStderr, result.getStderr());
    }

    // ============================== C. 输出磁盘上限 ==============================

    @Test
    void run_stdoutOverOutputLimit_terminatesProcessAndFlagsOutputLimit() throws Exception {
        TrackingRunner tracking = createTrackingRunner(0);
        useExecutable(tracking, createFakeMaven());
        setMaxOutputBytes(tracking, 64L);

        ProcessResult result = tracking.run(workDir,
                new String[]{"flood-stdout", "1024"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(-1, result.getExitCode());
        assertFalse(result.isTimedOut(), "输出超限主动终止不能标记为 TIMEOUT");
        assertTrue(result.isOutputLimitExceeded());
        assertTrue(result.getStdout().contains("X"),
                "Runner 应仍能从临时文件读取到已产生的 stdout");

        FakeMavenProcessSupport.ProcessRecord helper = awaitHelperRecord("flood-stdout");
        assertTrue(FakeMavenProcessSupport.awaitProcessGone(helper, PROCESS_EXIT_TIMEOUT),
                "输出超限后根进程及其可发现后代应被终止: " + helper);
        assertEquals(2, tracking.createdFiles().size());
        assertAllFilesDeleted(tracking.createdFiles());
        assertEquals(0, countFiles(tracking.outputDirectory()));
    }

    @Test
    void run_stderrOverOutputLimit_terminatesProcessAndFlagsOutputLimit() throws Exception {
        TrackingRunner tracking = createTrackingRunner(0);
        useExecutable(tracking, createFakeMaven());
        setMaxOutputBytes(tracking, 64L);

        ProcessResult result = tracking.run(workDir,
                new String[]{"flood-stderr", "1024"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(-1, result.getExitCode());
        assertFalse(result.isTimedOut(), "输出超限主动终止不能标记为 TIMEOUT");
        assertTrue(result.isOutputLimitExceeded());
        assertTrue(result.getStderr().contains("Y"),
                "Runner 应仍能从临时文件读取到已产生的 stderr");

        FakeMavenProcessSupport.ProcessRecord helper = awaitHelperRecord("flood-stderr");
        assertTrue(FakeMavenProcessSupport.awaitProcessGone(helper, PROCESS_EXIT_TIMEOUT),
                "输出超限后根进程及其可发现后代应被终止: " + helper);
        assertAllFilesDeleted(tracking.createdFiles());
        assertEquals(0, countFiles(tracking.outputDirectory()));
    }

    @Test
    void run_bothOutputsUnderOutputLimit_normalExitAndNoLimitFlag() throws Exception {
        useExecutable(createFakeMaven());
        setMaxOutputBytes(runner, 1024L);

        ProcessResult result = runner.run(workDir,
                new String[]{"output"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(0, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertFalse(result.isOutputLimitExceeded());
        assertEquals("STDOUT-LINE\n", result.getStdout());
        assertEquals("STDERR-LINE\n", result.getStderr());
    }

    @Test
    void run_eachOutputUnderLimit_doesNotSumStreams() throws Exception {
        useExecutable(createFakeMaven());
        // stdout/stderr 各 16 字节，单流均不超过 20 字节；二者相加会超过 20，但不应触发超限。
        setMaxOutputBytes(runner, 20L);

        ProcessResult result = runner.run(workDir,
                new String[]{"truncate-both", "15"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(0, result.getExitCode());
        assertFalse(result.isOutputLimitExceeded());
        assertEquals("X".repeat(15) + "\n", result.getStdout());
        assertEquals("Y".repeat(15) + "\n", result.getStderr());
    }

    @Test
    void run_outputExactlyAtLimit_isNotTreatedAsExceeded() throws Exception {
        useExecutable(createFakeMaven());
        setMaxOutputBytes(runner, 16L);

        ProcessResult result = runner.run(workDir,
                new String[]{"exact", "16"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(0, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertFalse(result.isOutputLimitExceeded(),
                "文件大小恰好等于上限时不能使用 >= 判断");
        assertEquals("X".repeat(15) + "\n", result.getStdout());
    }

    @Test
    void run_naturalExitWithOutputOverLimit_preservesActualExitCode() throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("final-check-output"));
        FinalCheckRunner finalCheck = new FinalCheckRunner(outputDir, 7, 65);
        setMaxOutputBytes(finalCheck, 64L);
        useExecutable(finalCheck, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = finalCheck.run(workDir,
                new String[]{"unused"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode(), "自然退出后才发现超限时必须保留真实退出码");
        assertFalse(result.isTimedOut());
        assertTrue(result.isOutputLimitExceeded());
        assertEquals(2, finalCheck.createdFiles().size());
        assertAllFilesDeleted(finalCheck.createdFiles());
        assertEquals(0, countFiles(outputDir));
    }

    // ============================== C2. 正常退出后的已观测后代清理 ==============================

    @Test
    void run_parentNaturalExit_cleansObservedLiveChildAndPreservesExitCode() throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("natural-exit-child"));
        ObservedTrackingRunner tracking = new ObservedTrackingRunner(outputDir);
        useExecutable(tracking, createFakeMaven());
        Path release = tempDir.resolve("release-exiter.txt");
        AsyncRun<ProcessResult> call = null;
        try {
            call = AsyncRun.start("natural-exit-child",
                    () -> tracking.run(workDir,
                            new String[]{"exit-with-inherited-child-after-release"},
                            RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH));

            FakeMavenProcessSupport.ProcessRecord exiter = awaitHelperRecord("exiter");
            FakeMavenProcessSupport.ProcessRecord child = awaitHelperRecord("child");
            FakeMavenProcessSupport.awaitFile(tempDir.resolve("exiter.ready"),
                    FakeMavenProcessSupport.READY_TIMEOUT);

            assertNotEquals(exiter.pid(), child.pid(), "父进程和子进程必须记录不同 PID");
            assertTrue(isAlive(child), "释放父进程前，子进程必须仍存活: " + child);

            // 明确等待 Runner 的观测回调，而不是用固定毫秒数推断子进程已被发现。
            tracking.awaitObserved(FakeMavenProcessSupport.READY_TIMEOUT, child.pid());
            tracking.expectDescendantsGoneAtOutputRead(child);
            Files.writeString(release, "release\n", StandardCharsets.UTF_8);

            ProcessResult result = call.await(RESULT_TIMEOUT);

            assertEquals(0, result.getExitCode(), "根进程自然退出后必须保留真实退出码");
            assertFalse(result.isTimedOut());
            assertFalse(result.isOutputLimitExceeded());
            assertTrue(FakeMavenProcessSupport.awaitProcessGone(child, PROCESS_EXIT_TIMEOUT),
                    "根进程自然退出后，已观测的直接子进程必须被清理: " + child);
            assertFalse(tracking.descendantsAliveWhenOutputRead(),
                    "输出读取必须发生在已观测后代清理之后");
            assertEquals(2, tracking.createdFiles().size());
            assertAllFilesDeleted(tracking.createdFiles());
            assertEquals(0, countFiles(outputDir));
        } finally {
            releaseMarker(release);
            cleanupAsync(call);
        }
    }

    @Test
    void run_parentNaturalExit_afterObservedChildWritesOutput_readsCompleteMarkers()
            throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("natural-exit-child-output"));
        ObservedTrackingRunner tracking = new ObservedTrackingRunner(outputDir);
        useExecutable(tracking, createFakeMaven());
        Path release = tempDir.resolve("release-exiter.txt");
        AsyncRun<ProcessResult> call = null;
        try {
            call = AsyncRun.start("natural-exit-child-output",
                    () -> tracking.run(workDir,
                            new String[]{"exit-with-inherited-child-after-release"},
                            RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH));

            FakeMavenProcessSupport.ProcessRecord exiter = awaitHelperRecord("exiter");
            FakeMavenProcessSupport.ProcessRecord child = awaitHelperRecord("child");
            FakeMavenProcessSupport.awaitFile(tempDir.resolve("exiter.ready"),
                    FakeMavenProcessSupport.READY_TIMEOUT);

            assertNotEquals(exiter.pid(), child.pid());
            tracking.awaitObserved(FakeMavenProcessSupport.READY_TIMEOUT, child.pid());
            tracking.expectDescendantsGoneAtOutputRead(child);
            Files.writeString(release, "release\n", StandardCharsets.UTF_8);

            ProcessResult result = call.await(RESULT_TIMEOUT);

            assertEquals(0, result.getExitCode());
            assertFalse(result.isTimedOut());
            assertEquals("STDOUT-CHILD-MARKER\n", result.getStdout(),
                    "清理子进程前已写入的 stdout 标记必须完整保留");
            assertEquals("STDERR-CHILD-MARKER\n", result.getStderr(),
                    "清理子进程前已写入的 stderr 标记必须完整保留");
            assertTrue(FakeMavenProcessSupport.awaitProcessGone(child, PROCESS_EXIT_TIMEOUT),
                    "读取输出后子进程必须已被清理: " + child);
            assertFalse(tracking.descendantsAliveWhenOutputRead());
            assertAllFilesDeleted(tracking.createdFiles());
            assertEquals(0, countFiles(outputDir));
        } finally {
            releaseMarker(release);
            cleanupAsync(call);
        }
    }

    @Test
    void run_parentNaturalExit_cleansObservedChildAndGrandchildProcesses() throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("natural-exit-tree"));
        ObservedTrackingRunner tracking = new ObservedTrackingRunner(outputDir);
        useExecutable(tracking, createFakeMaven());
        Path release = tempDir.resolve("release-tree.txt");
        AsyncRun<ProcessResult> call = null;
        try {
            call = AsyncRun.start("natural-exit-tree",
                    () -> tracking.run(workDir,
                            new String[]{"exit-with-child-tree-after-release"},
                            RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH));

            FakeMavenProcessSupport.ProcessRecord parent = awaitHelperRecord("parent-tree");
            FakeMavenProcessSupport.ProcessRecord child = awaitHelperRecord("child-tree");
            FakeMavenProcessSupport.ProcessRecord grandchild = awaitHelperRecord("grandchild");
            FakeMavenProcessSupport.awaitFile(tempDir.resolve("parent-tree.ready"),
                    FakeMavenProcessSupport.READY_TIMEOUT);

            assertNotEquals(parent.pid(), child.pid());
            assertNotEquals(child.pid(), grandchild.pid());
            assertTrue(isAlive(child), "释放根进程前，子进程必须仍存活: " + child);
            assertTrue(isAlive(grandchild), "释放根进程前，孙进程必须仍存活: " + grandchild);

            tracking.awaitObserved(FakeMavenProcessSupport.READY_TIMEOUT,
                    child.pid(), grandchild.pid());
            tracking.expectDescendantsGoneAtOutputRead(child, grandchild);
            Files.writeString(release, "release\n", StandardCharsets.UTF_8);

            ProcessResult result = call.await(RESULT_TIMEOUT);

            assertEquals(0, result.getExitCode(), "根进程自然退出后必须保留真实退出码");
            assertFalse(result.isTimedOut());
            assertFalse(result.isOutputLimitExceeded());
            assertTrue(FakeMavenProcessSupport.awaitProcessGone(child, PROCESS_EXIT_TIMEOUT),
                    "已观测的子进程必须被清理: " + child);
            assertTrue(FakeMavenProcessSupport.awaitProcessGone(grandchild, PROCESS_EXIT_TIMEOUT),
                    "已观测的孙进程必须被清理: " + grandchild);
            assertFalse(tracking.descendantsAliveWhenOutputRead(),
                    "输出读取必须发生在子进程和孙进程清理之后");
            assertAllFilesDeleted(tracking.createdFiles());
            assertEquals(0, countFiles(outputDir));
        } finally {
            releaseMarker(release);
            cleanupAsync(call);
        }
    }

    // ============================== C3. 身份校验与清理 deadline ==============================

    @Test
    void run_parentNaturalNonZeroExit_cleansObservedChildAndPreservesExitCode() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle child = mockProcessHandle(1101L, Instant.ofEpochMilli(1_000L), childAlive);
        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(child));
        cleanup.mapCurrentHandles(child);
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode(), "自然退出后清理后代不得改写真实退出码");
        assertFalse(result.isTimedOut());
        assertFalse(result.isOutputLimitExceeded());
        assertEquals(1, cleanup.observedCount(), "测试必须确认 Runner 已观测到该后代");
        verify(child).destroy();
        assertFalse(childAlive.get(), "身份确认一致的后代应被清理");
    }

    @Test
    void run_observedDescendantWithoutStartInstant_isNotTerminated() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle child = mockProcessHandle(1102L, null, childAlive);
        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(child));
        cleanup.mapCurrentHandles(child);
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertEquals(1, cleanup.observedCount());
        assertTrue(childAlive.get(), "启动时间缺失时不得冒险终止该进程");
        verify(child, never()).destroy();
        verify(child, never()).destroyForcibly();
    }

    @Test
    void run_observedDescendantWithDifferentStartInstant_isNotTerminated() {
        AtomicBoolean observedAlive = new AtomicBoolean(true);
        AtomicBoolean currentAlive = new AtomicBoolean(true);
        ProcessHandle observed = mockProcessHandle(1103L,
                Instant.ofEpochMilli(1_000L), observedAlive);
        ProcessHandle current = mockProcessHandle(1103L,
                Instant.ofEpochMilli(2_000L), currentAlive);
        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(observed));
        cleanup.mapCurrentHandles(current);
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertEquals(1, cleanup.observedCount());
        assertTrue(currentAlive.get(), "PID 复用后的新进程不能被终止");
        verify(observed, never()).destroy();
        verify(observed, never()).destroyForcibly();
        verify(current, never()).destroy();
        verify(current, never()).destroyForcibly();
    }

    @Test
    void run_observedDescendantMissingFromProcessHandle_isNotTerminated() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle child = mockProcessHandle(1104L, Instant.ofEpochMilli(1_000L), childAlive);
        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(child));
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertEquals(1, cleanup.observedCount());
        assertTrue(childAlive.get(), "无法确认身份时不得仅凭 PID 终止");
        verify(child, never()).destroy();
        verify(child, never()).destroyForcibly();
    }

    @Test
    void run_observedDescendantIdentityLookupThrows_isNotTerminated() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle child = mockProcessHandle(1105L, Instant.ofEpochMilli(1_000L), childAlive);
        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(child));
        cleanup.failIdentityLookup();
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertEquals(1, cleanup.observedCount());
        assertTrue(childAlive.get(), "身份查询异常时不得终止后代");
        verify(child, never()).destroy();
        verify(child, never()).destroyForcibly();
    }

    @Test
    void run_oneObservedDescendantTerminationFails_otherDescendantStillCleaned() {
        Instant start = Instant.ofEpochMilli(1_000L);
        AtomicBoolean failingAlive = new AtomicBoolean(true);
        AtomicBoolean healthyAlive = new AtomicBoolean(true);
        ProcessHandle failing = mockProcessHandle(1106L, start, failingAlive);
        ProcessHandle healthy = mockProcessHandle(1107L, start, healthyAlive);
        // 两者都忽略优雅终止，强制终止阶段验证单个失败不会中断后续后代。
        doAnswer(invocation -> false).when(failing).destroy();
        doAnswer(invocation -> false).when(healthy).destroy();
        doThrow(new IllegalStateException("injected descendant termination failure"))
                .when(failing).destroyForcibly();

        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(failing, healthy))
                .withGracefulTimeout(Duration.ofMillis(1))
                .withTerminationTimeout(Duration.ofMillis(150));
        cleanup.mapCurrentHandles(failing, healthy);
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode(), "后代清理失败不得改写根进程退出码");
        verify(failing).destroyForcibly();
        verify(healthy).destroyForcibly();
        assertFalse(healthyAlive.get(), "一个后代终止失败后，其他身份确认的后代仍必须继续清理");
        assertTrue(failingAlive.get());
    }

    @Test
    void run_cleanupDeadlineExpired_doesNotStartAnyDescendantTermination() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle child = mockProcessHandle(1108L, Instant.ofEpochMilli(1_000L), childAlive);
        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(child))
                .withTerminationTimeout(Duration.ZERO);
        cleanup.mapCurrentHandles(child);
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertEquals(1, cleanup.observedCount());
        assertTrue(childAlive.get());
        verify(child, never()).destroy();
        verify(child, never()).destroyForcibly();
    }

    @Test
    void run_pollingOutputLimitAfterRootNaturalExit_stillCleansObservedDescendant() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle child = mockProcessHandle(1109L, Instant.ofEpochMilli(1_000L), childAlive);
        PollingAnomalyRunner anomaly = new PollingAnomalyRunner(tempDir,
                PollingAnomaly.OUTPUT_LIMIT, child, 65);
        setMaxOutputBytes(anomaly, 64L);
        useExecutable(anomaly, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = anomaly.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode(), "轮询阶段发现超限但根进程已退出时必须保留真实退出码");
        assertFalse(result.isTimedOut());
        assertTrue(result.isOutputLimitExceeded());
        assertEquals(1, anomaly.observedCount());
        verify(child).destroy();
        assertFalse(childAlive.get(), "轮询阶段发现输出异常不能跳过已观测后代清理");
        assertFalse(anomaly.descendantsAliveWhenOutputRead());
    }

    @Test
    void run_pollingSizeCheckFailureAfterRootNaturalExit_stillCleansObservedDescendant() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle child = mockProcessHandle(1110L, Instant.ofEpochMilli(1_000L), childAlive);
        PollingAnomalyRunner anomaly = new PollingAnomalyRunner(tempDir,
                PollingAnomaly.SIZE_CHECK_FAILURE, child, 0);
        setMaxOutputBytes(anomaly, 64L);
        useExecutable(anomaly, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultMavenProcessRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ProcessResult result;
        try {
            result = anomaly.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                    DEFAULT_MAX_LOG_LENGTH);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(7, result.getExitCode(), "大小检查失败但根进程已退出时必须保留真实退出码");
        assertFalse(result.isTimedOut());
        assertFalse(result.isOutputLimitExceeded(),
                "大小检查失败不能被伪造成输出超限");
        assertEquals(1, anomaly.observedCount());
        verify(child).destroy();
        assertFalse(childAlive.get(), "轮询阶段大小检查失败不能跳过已观测后代清理");
        assertFalse(anomaly.descendantsAliveWhenOutputRead());
        assertTrue(appender.list.stream().anyMatch(event ->
                        event.getLevel() == Level.WARN
                                && event.getFormattedMessage().contains("保留真实退出码")),
                "必须保留 outputSizeCheckFailed 的原始语义并记录自然退出");
    }

    @Test
    void terminateProcessTree_revalidatesDescendantIdentityBeforeForcibleKill() {
        AtomicBoolean observedAlive = new AtomicBoolean(true);
        AtomicBoolean currentAlive = new AtomicBoolean(true);
        ProcessHandle observed = mockProcessHandle(1111L,
                Instant.ofEpochMilli(1_000L), observedAlive);
        ProcessHandle current = mockProcessHandle(1111L,
                Instant.ofEpochMilli(2_000L), currentAlive);
        ActiveTerminationRunner termination = new ActiveTerminationRunner(observed, current);
        Process root = termination.createProcess();

        ReflectionTestUtils.invokeMethod(termination, "terminateProcessTree", root);

        assertEquals(1, termination.observedCount());
        verify(root, atLeastOnce()).destroyForcibly();
        verify(observed, never()).destroyForcibly();
        verify(current, never()).destroyForcibly();
        assertTrue(currentAlive.get(), "主动终止路径同样不得杀死 PID 复用后的新进程");
    }

    // ============================== C4. 后代扫描 deadline 与自然退出最终快照 ==============================

    @Test
    void run_scanDeadlineExpiredDuringIdentityChecks_doesNotQueryRemainingDescendants() {
        Instant start = Instant.ofEpochMilli(1_000L);
        ProcessHandle first = mockProcessHandle(1112L, start, new AtomicBoolean(true));
        ProcessHandle second = mockProcessHandle(1113L, start, new AtomicBoolean(true));
        ProcessHandle third = mockProcessHandle(1114L, start, new AtomicBoolean(true));
        ProcessHandle fourth = mockProcessHandle(1115L, start, new AtomicBoolean(true));
        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(first, second, third, fourth))
                .withTerminationTimeout(Duration.ofMillis(150))
                .withIdentityLookupDelay(Duration.ofMillis(60));
        cleanup.mapCurrentHandles(first, second, third, fourth);
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertEquals(4, cleanup.observedCount());
        assertTrue(cleanup.identityLookups() < 4,
                "deadline 到期后不得继续为剩余后代发起身份查询, lookups="
                        + cleanup.identityLookups());
        verify(fourth, never()).destroy();
        verify(fourth, never()).destroyForcibly();
    }

    @Test
    void run_scanDeadlineExpired_reportsUncheckedInsteadOfConfirmedExit() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle child = mockProcessHandle(1116L, Instant.ofEpochMilli(1_000L), childAlive);
        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(child))
                .withTerminationTimeout(Duration.ofMillis(100))
                .withIdentityLookupDelay(Duration.ofMillis(200));
        cleanup.mapCurrentHandles(child);
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultMavenProcessRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ProcessResult result;
        try {
            result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                    DEFAULT_MAX_LOG_LENGTH);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(7, result.getExitCode());
        assertEquals(1, cleanup.identityLookups(),
                "deadline 到期后不得再为后代发起身份查询");
        assertTrue(childAlive.get(), "未检查的后代不得被当作已退出或已终止");
        verify(child, never()).destroy();
        verify(child, never()).destroyForcibly();
        assertTrue(appender.list.stream().anyMatch(event ->
                        event.getLevel() == Level.WARN
                                && event.getFormattedMessage().contains("未检查")
                                && event.getFormattedMessage().contains("1116")),
                "deadline 到期必须记录未检查的后代数量或 PID");
        assertFalse(appender.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("Maven 已观测后代进程清理完成")),
                "存在未检查后代时不得报告清理完成");
    }

    @Test
    void waitForObservedDescendantsExit_deadlineAlreadyExpired_doesNotQueryDescendants() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle child = mockProcessHandle(1117L, Instant.ofEpochMilli(1_000L), childAlive);
        IdentityRunner cleanup = new IdentityRunner(tempDir, 7, List.of(child))
                .withTerminationTimeout(Duration.ofMillis(100))
                .withGracefulTimeout(Duration.ZERO)
                .withIdentityLookupDelay(Duration.ofMillis(200));
        cleanup.mapCurrentHandles(child);
        useExecutable(cleanup, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = cleanup.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertEquals(1, cleanup.identityLookups(),
                "等待阶段的扫描必须在 deadline 到期后停止，不得继续查询身份");
        assertTrue(childAlive.get());
        verify(child, never()).destroyForcibly();
    }

    @Test
    void run_naturalExit_snapshotsDescendantsBeforeCleanup() {
        AtomicBoolean childAlive = new AtomicBoolean(true);
        ProcessHandle lateChild = mockProcessHandle(1118L, Instant.ofEpochMilli(1_000L), childAlive);
        NaturalExitSnapshotRunner snapshot = new NaturalExitSnapshotRunner(tempDir, 7)
                .withExitedDescendants(lateChild);
        snapshot.mapCurrentHandles(lateChild);
        useExecutable(snapshot, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = snapshot.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertEquals(1, snapshot.observedCount(), "自然退出后必须在清理前补充最终后代快照");
        verify(lateChild).destroy();
        assertFalse(childAlive.get(), "最终快照记录的后代必须参与自然退出清理");
    }

    @Test
    void run_naturalExitSnapshot_doesNotOverwriteReliableIdentity() {
        Instant start = Instant.ofEpochMilli(1_000L);
        AtomicBoolean alive = new AtomicBoolean(true);
        ProcessHandle reliable = mockProcessHandle(1119L, start, alive);
        ProcessHandle snapshotWithoutStart = mockProcessHandle(1119L, null, alive);
        NaturalExitSnapshotRunner snapshot = new NaturalExitSnapshotRunner(tempDir, 7)
                .withAliveDescendants(reliable)
                .withExitedDescendants(snapshotWithoutStart);
        snapshot.mapCurrentHandles(reliable);
        useExecutable(snapshot, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = snapshot.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertEquals(1, snapshot.observedCount(), "最终快照不得为已记录的后代产生新的身份");
        verify(reliable).destroy();
        assertFalse(alive.get(), "已有可靠启动时间的身份必须保留，不能被缺少时间的快照覆盖");
        verify(snapshotWithoutStart, never()).destroy();
        verify(snapshotWithoutStart, never()).destroyForcibly();
    }

    @Test
    void run_naturalExitSnapshotFailure_preservesRealExitCode() {
        NaturalExitSnapshotRunner snapshot = new NaturalExitSnapshotRunner(tempDir, 7)
                .withExitedSnapshotFailure();
        useExecutable(snapshot, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultMavenProcessRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ProcessResult result;
        try {
            result = snapshot.run(workDir, new String[]{"unused"}, RUN_TIMEOUT,
                    DEFAULT_MAX_LOG_LENGTH);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(7, result.getExitCode(), "后代快照失败不得影响根进程真实退出码");
        assertFalse(result.isTimedOut());
        assertFalse(result.isOutputLimitExceeded());
        assertEquals(0, snapshot.observedCount());
        assertTrue(appender.list.stream().anyMatch(event ->
                        event.getLevel() == Level.WARN
                                && event.getFormattedMessage().contains("补充后代快照失败")),
                "最终快照异常必须被隔离并记录 WARN");
    }

    // ============================== D. 输出文件大小检查失败保护 ==============================

    @Test
    void run_stdoutSizeCheckFailsConsecutively_terminatesProcessWithoutOutputLimitFlag()
            throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("size-failure-stdout"));
        SizeFailureRunner sizeFailure = new SizeFailureRunner(outputDir,
                (stream, call) -> "stdout".equals(stream), 7, 0, 0);
        setMaxOutputBytes(sizeFailure, 1024L);
        useExecutable(sizeFailure, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = sizeFailure.run(workDir,
                new String[]{"unused"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(-1, result.getExitCode());
        assertFalse(result.isTimedOut(), "大小检查失败主动终止不能标记为 TIMEOUT");
        assertFalse(result.isOutputLimitExceeded(),
                "没有证据证明文件超限时不能标记 outputLimitExceeded");
        assertTrue(sizeFailure.destroyCalls() > 0, "连续失败达到阈值必须终止根进程");
        assertEquals(2, sizeFailure.createdFiles().size());
        assertAllFilesDeleted(sizeFailure.createdFiles());
        assertEquals(0, countFiles(outputDir));
    }

    @Test
    void run_stderrSizeCheckFailsConsecutively_terminatesProcessWithoutOutputLimitFlag()
            throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("size-failure-stderr"));
        SizeFailureRunner sizeFailure = new SizeFailureRunner(outputDir,
                (stream, call) -> "stderr".equals(stream), 7, 0, 0);
        setMaxOutputBytes(sizeFailure, 1024L);
        useExecutable(sizeFailure, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = sizeFailure.run(workDir,
                new String[]{"unused"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(-1, result.getExitCode());
        assertFalse(result.isTimedOut(), "大小检查失败主动终止不能标记为 TIMEOUT");
        assertFalse(result.isOutputLimitExceeded(),
                "没有证据证明文件超限时不能标记 outputLimitExceeded");
        assertTrue(sizeFailure.destroyCalls() > 0, "连续失败达到阈值必须终止根进程");
        assertEquals(2, sizeFailure.createdFiles().size());
        assertAllFilesDeleted(sizeFailure.createdFiles());
        assertEquals(0, countFiles(outputDir));
    }

    @Test
    void run_singleSizeCheckFailure_thenSuccess_doesNotTerminateProcess() throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("size-failure-single"));
        SizeFailureRunner sizeFailure = new SizeFailureRunner(outputDir,
                (stream, call) -> "stdout".equals(stream) && call == 1, 7, 1, 0);
        setMaxOutputBytes(sizeFailure, 1024L);
        useExecutable(sizeFailure, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = sizeFailure.run(workDir,
                new String[]{"unused"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode(), "单次检查失败后恢复不应改变真实退出码");
        assertFalse(result.isTimedOut());
        assertFalse(result.isOutputLimitExceeded());
        assertEquals(0, sizeFailure.destroyCalls(), "单次失败未达阈值时不能终止进程");
        assertAllFilesDeleted(sizeFailure.createdFiles());
        assertEquals(0, countFiles(outputDir));
    }

    @Test
    void run_sizeCheckFailureCount_resetsAfterSuccessfulCheck() throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("size-failure-reset"));
        // 第 3 次成功检查应把前两次连续失败清零，因此第 4、5 次失败仍只有 2 次。
        SizeFailureRunner sizeFailure = new SizeFailureRunner(outputDir,
                (stream, call) -> "stdout".equals(stream)
                        && (call == 1 || call == 2 || call == 4 || call == 5),
                7, 3, 0);
        setMaxOutputBytes(sizeFailure, 1024L);
        useExecutable(sizeFailure, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = sizeFailure.run(workDir,
                new String[]{"unused"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertFalse(result.isOutputLimitExceeded());
        assertEquals(0, sizeFailure.destroyCalls(),
                "成功检查必须清除连续失败计数，不能累计到阈值");
        assertAllFilesDeleted(sizeFailure.createdFiles());
        assertEquals(0, countFiles(outputDir));
    }

    @Test
    void run_naturalExitBeforeSizeCheckFailureThreshold_preservesActualExitCode()
            throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("size-failure-natural-exit"));
        SizeFailureRunner sizeFailure = new SizeFailureRunner(outputDir,
                (stream, call) -> "stdout".equals(stream), 7, 0, 1);
        setMaxOutputBytes(sizeFailure, 1024L);
        useExecutable(sizeFailure, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = sizeFailure.run(workDir,
                new String[]{"unused"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(7, result.getExitCode(),
                "根进程已自然退出时必须保留真实退出码，而不是改写成主动终止的 -1");
        assertFalse(result.isTimedOut());
        assertFalse(result.isOutputLimitExceeded());
        assertEquals(0, sizeFailure.destroyCalls(),
                "根进程已退出时不应再执行主动终止");
        assertAllFilesDeleted(sizeFailure.createdFiles());
        assertEquals(0, countFiles(outputDir));
    }

    @Test
    void run_timeoutAndSizeCheckFailure_timedOutTakesPrecedence() throws Exception {
        Path outputDir = Files.createDirectories(tempDir.resolve("size-failure-timeout"));
        SizeFailureRunner sizeFailure = new SizeFailureRunner(outputDir,
                (stream, call) -> "stdout".equals(stream), 7, 0, 0)
                .delayFirstWait(Duration.ofMillis(100));
        setMaxOutputBytes(sizeFailure, 1024L);
        useExecutable(sizeFailure, tempDir.resolve(isWindows() ? "unused-mvn.cmd" : "unused-mvn"));

        ProcessResult result = sizeFailure.run(workDir,
                new String[]{"unused"}, Duration.ofMillis(30), DEFAULT_MAX_LOG_LENGTH);

        assertTrue(result.isTimedOut(), "超时与检查失败同时出现时必须优先标记 TIMEOUT");
        assertEquals(-1, result.getExitCode());
        assertFalse(result.isOutputLimitExceeded());
        assertTrue(sizeFailure.destroyCalls() > 0);
        assertAllFilesDeleted(sizeFailure.createdFiles());
        assertEquals(0, countFiles(outputDir));
    }

    // ============================== E. ProcessResult 兼容性 ==============================

    @Test
    void processResult_legacyFiveArgConstructor_defaultsOutputLimitExceededToFalse() {
        ProcessResult result = new ProcessResult(-1, "out", "err", true, 123L);

        assertEquals(-1, result.getExitCode());
        assertEquals("out", result.getStdout());
        assertEquals("err", result.getStderr());
        assertTrue(result.isTimedOut());
        assertEquals(123L, result.getExecutionTimeMs());
        assertFalse(result.isOutputLimitExceeded());
    }

    @Test
    void processResult_sixArgConstructor_preservesOutputLimitExceeded() {
        assertTrue(new ProcessResult(1, "", "", false, 1L, true)
                .isOutputLimitExceeded());
        assertFalse(new ProcessResult(0, "", "", false, 1L, false)
                .isOutputLimitExceeded());
    }

    // ============================== E. 配置校验 ==============================

    @Test
    void resolveMaxOutputBytes_nonPositive_usesDefaultAndLogsWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultMavenProcessRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (long invalid : new long[]{0L, -1L}) {
                appender.list.clear();
                ReflectionTestUtils.setField(runner, "maxOutputBytes", invalid);

                assertEquals(DEFAULT_MAX_OUTPUT_BYTES, runner.resolveMaxOutputBytes(),
                        "非正数配置必须回退到默认 10 MiB: " + invalid);
                assertTrue(appender.list.stream().anyMatch(event ->
                                event.getLevel() == Level.WARN
                                        && event.getFormattedMessage().contains("max-output-bytes")),
                        "无效配置必须记录 WARN: " + invalid);
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void run_missingExecutable_returnsStartupFailureResult() {
        Path missing = tempDir.resolve(isWindows() ? "missing-mvn.cmd" : "missing-mvn");
        useExecutable(missing);

        ProcessResult result = runner.run(workDir,
                new String[]{"args"}, RUN_TIMEOUT, 1024);

        assertEquals(-1, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertEquals("", result.getStdout());
        assertTrue(result.getStderr().startsWith("无法启动 Maven: "),
                "实际 stderr: " + result.getStderr());
        assertTrue(result.getStderr().contains("请确认已安装 Maven"),
                "实际 stderr: " + result.getStderr());
        assertTrue(result.getExecutionTimeMs() >= 0);
    }

    @Test
    void run_missingWorkingDirectory_returnsStartupFailureResult() throws Exception {
        Path fakeMaven = createFakeMaven();
        useExecutable(fakeMaven);
        Path missingWorkDir = tempDir.resolve("missing-work-dir");

        ProcessResult result = runner.run(missingWorkDir,
                new String[]{"args"}, RUN_TIMEOUT, 1024);

        assertEquals(-1, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertEquals("", result.getStdout());
        assertTrue(result.getStderr().startsWith("无法启动 Maven: "),
                "实际 stderr: " + result.getStderr());
        assertTrue(result.getStderr().contains("请确认已安装 Maven"),
                "实际 stderr: " + result.getStderr());
        assertTrue(result.getExecutionTimeMs() >= 0);
    }

    // ============================== D. 分块输出读取 ==============================

    @Test
    void readStream_emptyFile_returnsEmptyString() {
        assertEquals("", readStream(new byte[0], DEFAULT_MAX_LOG_LENGTH));
    }

    @Test
    void readStream_multiLineOutput_appendsTrailingNewline() {
        assertEquals("first\nsecond\n",
                readStream(utf8("first\nsecond\n"), DEFAULT_MAX_LOG_LENGTH));
    }

    @Test
    void readStream_emptyLines_arePreserved() {
        assertEquals("a\n\nb\n",
                readStream(utf8("a\n\nb\n"), DEFAULT_MAX_LOG_LENGTH));
    }

    @Test
    void readStream_crlf_isNormalizedToLf() {
        assertEquals("a\nb\n",
                readStream(utf8("a\r\nb\r\n"), DEFAULT_MAX_LOG_LENGTH));
    }

    @Test
    void readStream_loneCarriageReturn_isTreatedAsLineTerminator() {
        assertEquals("a\nb\n",
                readStream(utf8("a\rb\r"), DEFAULT_MAX_LOG_LENGTH));
    }

    @Test
    void readStream_noTrailingNewline_stillGetsOne() {
        assertEquals("a\nb\n",
                readStream(utf8("a\nb"), DEFAULT_MAX_LOG_LENGTH));
    }

    @Test
    void readStream_mixedLineEndings_matchReadLineSemantics() {
        assertEquals("a\nb\nc\nd\n",
                readStream(utf8("a\r\nb\rc\nd"), DEFAULT_MAX_LOG_LENGTH));
    }

    @Test
    void readStream_exactRemainingSpace_isNotTruncated() {
        int maxLogLength = 10;
        String content = "X".repeat(maxLogLength - 1) + "\n";

        assertEquals(content, readStream(utf8(content), maxLogLength));
    }

    @Test
    void readStream_exceedsRemainingSpace_appendsOriginalMarker() {
        int maxLogLength = 10;
        String content = "X".repeat(maxLogLength) + "\n";

        assertEquals("X".repeat(maxLogLength) + TRUNCATION_MARKER,
                readStream(utf8(content), maxLogLength));
    }

    @Test
    void readStream_longSingleLine_truncatesAtBoundWithoutBufferingWholeLine() {
        int maxLogLength = 1_000;
        String content = "X".repeat(100_000) + "\n";

        String actual = readStream(utf8(content), maxLogLength);

        assertEquals("X".repeat(maxLogLength) + TRUNCATION_MARKER, actual);
    }

    @Test
    void readStream_truncationBoundary_doesNotSplitSurrogatePair() {
        String emoji = "\uD83D\uDE00";
        assertEquals("A" + emoji + TRUNCATION_MARKER,
                readStream(utf8("A" + emoji), 3));
        assertEquals("A" + TRUNCATION_MARKER,
                readStream(utf8("A" + emoji), 2),
                "只剩 1 个字符空间时不能写入孤立高代理字符");
    }

    @Test
    void readStream_nonPositiveMaxLength_returnsMarkerForNonEmptyOutput() {
        assertEquals("", readStream(new byte[0], 0));
        assertEquals(TRUNCATION_MARKER, readStream(utf8("abc"), 0));
        assertEquals(TRUNCATION_MARKER, readStream(utf8("\n"), 0));
        assertEquals(TRUNCATION_MARKER, readStream(utf8("abc"), -5));
    }

    // ============================== E. 临时文件清理 ==============================

    @Test
    void run_normalExecution_cleansBothTemporaryOutputFiles() throws Exception {
        TrackingRunner tracking = createTrackingRunner(0);
        useExecutable(tracking, createFakeMaven());

        ProcessResult result = tracking.run(workDir,
                new String[]{"output"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH);

        assertEquals(0, result.getExitCode());
        assertEquals("STDOUT-LINE\n", result.getStdout());
        assertEquals("STDERR-LINE\n", result.getStderr());
        assertEquals(2, tracking.createdFiles().size());
        assertAllFilesDeleted(tracking.createdFiles());
        assertEquals(0, countFiles(tracking.outputDirectory()));
    }

    @Test
    void run_missingExecutable_cleansTemporaryOutputFiles() throws Exception {
        TrackingRunner tracking = createTrackingRunner(0);
        Path missing = tempDir.resolve(isWindows() ? "missing-mvn.cmd" : "missing-mvn");
        useExecutable(tracking, missing);

        ProcessResult result = tracking.run(workDir,
                new String[]{"args"}, RUN_TIMEOUT, 1024);

        assertEquals(-1, result.getExitCode());
        assertEquals(2, tracking.createdFiles().size());
        assertAllFilesDeleted(tracking.createdFiles());
        assertEquals(0, countFiles(tracking.outputDirectory()));
    }

    @Test
    void run_secondTempFileCreationFails_cleansAlreadyCreatedFile() throws Exception {
        TrackingRunner tracking = createTrackingRunner(2);
        Path missing = tempDir.resolve(isWindows() ? "missing-mvn.cmd" : "missing-mvn");
        useExecutable(tracking, missing);

        ProcessResult result = tracking.run(workDir,
                new String[]{"args"}, RUN_TIMEOUT, 1024);

        assertEquals(-1, result.getExitCode());
        assertEquals(1, tracking.createdFiles().size());
        assertAllFilesDeleted(tracking.createdFiles());
        assertEquals(0, countFiles(tracking.outputDirectory()));
    }

    @Test
    void run_tempFileCreationRuntimeException_cleansAlreadyCreatedFileAndRethrows()
            throws Exception {
        TrackingRunner tracking = createTrackingRunner(2);
        tracking.failCreateWithRuntimeException();
        Path missing = tempDir.resolve(isWindows() ? "missing-mvn.cmd" : "missing-mvn");
        useExecutable(tracking, missing);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> tracking.run(workDir, new String[]{"args"}, RUN_TIMEOUT, 1024));

        assertTrue(failure.getMessage().contains("injected temp file creation failure"));
        assertEquals(1, tracking.createdFiles().size());
        assertAllFilesDeleted(tracking.createdFiles());
        assertEquals(0, countFiles(tracking.outputDirectory()));
    }

    @Test
    void run_stdoutReadFails_stillCleansBothTemporaryFiles() throws Exception {
        TrackingRunner tracking = createTrackingRunner(0);
        tracking.failReadFor(".stdout");
        useExecutable(tracking, createFakeMaven());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> tracking.run(workDir,
                        new String[]{"output"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH));

        assertTrue(failure.getMessage().contains("injected read failure"));
        assertEquals(2, tracking.createdFiles().size());
        assertAllFilesDeleted(tracking.createdFiles());
        assertEquals(0, countFiles(tracking.outputDirectory()));
    }

    @Test
    void run_stderrReadFails_stillCleansBothTemporaryFiles() throws Exception {
        TrackingRunner tracking = createTrackingRunner(0);
        tracking.failReadFor(".stderr");
        useExecutable(tracking, createFakeMaven());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> tracking.run(workDir,
                        new String[]{"output"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH));

        assertTrue(failure.getMessage().contains("injected read failure"));
        assertEquals(2, tracking.createdFiles().size());
        assertAllFilesDeleted(tracking.createdFiles());
        assertEquals(0, countFiles(tracking.outputDirectory()));
    }

    @Test
    void run_cleanupAlsoFails_originalReadFailureIsPreserved() throws Exception {
        TrackingRunner tracking = createTrackingRunner(0);
        tracking.failReadFor(".stdout");
        tracking.failCleanup();
        useExecutable(tracking, createFakeMaven());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> tracking.run(workDir,
                        new String[]{"output"}, RUN_TIMEOUT, DEFAULT_MAX_LOG_LENGTH));

        assertTrue(failure.getMessage().contains("injected read failure"),
                "清理异常不得覆盖原始读取异常: " + failure.getMessage());
        assertFalse(failure.getMessage().contains("injected cleanup failure"));
        assertEquals(2, tracking.createdFiles().size());
        assertAllFilesDeleted(tracking.createdFiles());
    }

    private TrackingRunner createTrackingRunner(int failCreateOnCall) throws IOException {
        Path outputDir = Files.createDirectories(tempDir.resolve("tracked-output"));
        return new TrackingRunner(outputDir, failCreateOnCall);
    }

    private static void assertAllFilesDeleted(List<Path> files) {
        for (Path file : files) {
            assertFalse(Files.exists(file), "临时文件未清理: " + file);
        }
    }

    private static long countFiles(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.count();
        } catch (IOException e) {
            throw new AssertionError("无法列出临时输出目录: " + directory, e);
        }
    }

    private static boolean isAlive(FakeMavenProcessSupport.ProcessRecord record) {
        return ProcessHandle.of(record.pid())
                .filter(record::matches)
                .map(ProcessHandle::isAlive)
                .orElse(false);
    }

    private static void releaseMarker(Path release) {
        try {
            if (!Files.exists(release)) {
                Files.writeString(release, "release\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("[WARN] 无法写入测试释放标记: " + release + " - " + e);
        }
    }

    private void cleanupAsync(AsyncRun<?> call) {
        if (call != null) {
            call.interrupt();
        }
        try {
            processRegistry.trackRecords(tempDir);
        } finally {
            processRegistry.terminateAll();
        }
        if (call != null && !call.awaitTermination(CLEANUP_TIMEOUT)) {
            System.err.println("[WARN] Runner 执行线程未在清理期限内退出");
        }
    }

    private static void awaitCondition(BooleanSupplier condition, Duration timeout,
                                       String failureMessage) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AssertionError("等待条件时线程被中断: " + failureMessage);
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(failureMessage);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    /**
     * 至少消耗指定时长。parkNanos 可能提前返回（或被中断立即返回），因此循环到目标时间，
     * 让身份查询按可预测的预算推进，从而稳定验证 deadline 到期后的扫描行为。
     */
    private static void awaitAtLeast(Duration duration) {
        long target = System.nanoTime() + duration.toNanos();
        long remaining;
        while ((remaining = target - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    /**
     * 只替换临时文件生命周期中的三个包级接缝，用于稳定注入创建/读取/清理失败。
     */
    private static final class TrackingRunner extends DefaultMavenProcessRunner {

        private final Path outputDir;
        private final int failCreateOnCall;
        private final List<Path> createdFiles = new ArrayList<>();
        private String failReadSuffix;
        private boolean failCleanup;
        private boolean runtimeCreateFailure;

        private TrackingRunner(Path outputDir, int failCreateOnCall) {
            this.outputDir = outputDir;
            this.failCreateOnCall = failCreateOnCall;
        }

        @Override
        Path createOutputTempFile(String prefix, String suffix) throws IOException {
            if (failCreateOnCall > 0 && createdFiles.size() + 1 == failCreateOnCall) {
                if (runtimeCreateFailure) {
                    throw new IllegalStateException("injected temp file creation failure");
                }
                throw new IOException("injected temp file creation failure");
            }
            Path file = Files.createTempFile(outputDir, prefix, suffix);
            createdFiles.add(file);
            return file;
        }

        @Override
        String readOutputFile(Path file, int maxLogLength) {
            if (failReadSuffix != null
                    && file.getFileName().toString().endsWith(failReadSuffix)) {
                throw new IllegalStateException("injected read failure: " + file.getFileName());
            }
            return super.readOutputFile(file, maxLogLength);
        }

        @Override
        void deleteOutputFile(Path file) {
            super.deleteOutputFile(file);
            if (failCleanup) {
                throw new IllegalStateException("injected cleanup failure");
            }
        }

        private void failReadFor(String suffix) {
            this.failReadSuffix = suffix;
        }

        private void failCleanup() {
            this.failCleanup = true;
        }

        private void failCreateWithRuntimeException() {
            this.runtimeCreateFailure = true;
        }

        private List<Path> createdFiles() {
            return createdFiles;
        }

        private Path outputDirectory() {
            return outputDir;
        }
    }

    /**
     * daemon 工作线程，让测试可以在 Runner 执行期间等待明确的进程观测信号并写入释放标记。
     */
    private static final class AsyncRun<T> {

        private final Thread thread;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<T> result = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private AsyncRun(String name, Supplier<T> action) {
            thread = new Thread(() -> {
                try {
                    result.set(action.get());
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    finished.countDown();
                }
            }, name);
            thread.setDaemon(true);
            thread.start();
        }

        static <T> AsyncRun<T> start(String name, Supplier<T> action) {
            return new AsyncRun<>(name, action);
        }

        T await(Duration timeout) throws Exception {
            if (!finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Runner 未在 " + timeout + " 内返回");
            }
            Throwable throwable = failure.get();
            if (throwable != null) {
                throw new AssertionError("Runner 执行抛出异常", throwable);
            }
            T value = result.get();
            assertNotNull(value, "Runner 已结束但没有返回结果");
            return value;
        }

        void interrupt() {
            thread.interrupt();
        }

        boolean awaitTermination(Duration timeout) {
            try {
                return finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * 在真实 Runner 上增加两个测试观测点：
     * 1. 后代被记录时发出明确信号，避免用固定毫秒数推断；
     * 2. 读取输出时检查指定后代是否仍存活，验证读取发生在清理之后。
     */
    private static final class ObservedTrackingRunner extends DefaultMavenProcessRunner {

        private final Path outputDir;
        private final List<Path> createdFiles = new ArrayList<>();
        private final Set<Long> observedDescendantPids = ConcurrentHashMap.newKeySet();
        private volatile List<FakeMavenProcessSupport.ProcessRecord> descendantsExpectedGone = List.of();
        private volatile boolean descendantsAliveWhenOutputRead;

        private ObservedTrackingRunner(Path outputDir) {
            this.outputDir = outputDir;
        }

        @Override
        Path createOutputTempFile(String prefix, String suffix) throws IOException {
            Path file = Files.createTempFile(outputDir, prefix, suffix);
            createdFiles.add(file);
            return file;
        }

        @Override
        void descendantObserved(ProcessHandle descendant) {
            observedDescendantPids.add(descendant.pid());
        }

        private void awaitObserved(Duration timeout, long firstPid, long... remainingPids) {
            awaitCondition(() -> {
                if (!observedDescendantPids.contains(firstPid)) {
                    return false;
                }
                for (long pid : remainingPids) {
                    if (!observedDescendantPids.contains(pid)) {
                        return false;
                    }
                }
                return true;
            }, timeout,
                    "Runner 未在期限内观测到后代进程, firstPid=" + firstPid
                            + ", observed=" + observedDescendantPids);
        }

        private void expectDescendantsGoneAtOutputRead(
                FakeMavenProcessSupport.ProcessRecord... records) {
            this.descendantsExpectedGone = List.of(records);
        }

        @Override
        String readOutputFile(Path file, int maxLogLength) {
            if (descendantsExpectedGone.stream().anyMatch(DefaultMavenProcessRunnerTest::isAlive)) {
                descendantsAliveWhenOutputRead = true;
            }
            return super.readOutputFile(file, maxLogLength);
        }

        private boolean descendantsAliveWhenOutputRead() {
            return descendantsAliveWhenOutputRead;
        }

        private List<Path> createdFiles() {
            return createdFiles;
        }
    }


    /**
     * 创建可控的 ProcessHandle，用于稳定验证身份校验不会退化为 PID-only 终止。
     */
    private static ProcessHandle mockProcessHandle(long pid, Instant startInstant,
                                                   AtomicBoolean alive) {
        ProcessHandle handle = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(handle.pid()).thenReturn(pid);
        when(handle.info()).thenReturn(info);
        when(info.startInstant()).thenReturn(Optional.ofNullable(startInstant));
        when(handle.isAlive()).thenAnswer(invocation -> alive.get());
        when(handle.destroy()).thenAnswer(invocation -> {
            alive.set(false);
            return true;
        });
        when(handle.destroyForcibly()).thenAnswer(invocation -> {
            alive.set(false);
            return true;
        });
        return handle;
    }

    /**
     * 直接驱动主动终止路径：根进程始终可被终止，但后代 PID 已对应不同 startInstant。
     * 用于验证 terminateProcessTree 不会仅凭旧 ProcessHandle 或 PID 杀死后代。
     */
    private static final class ActiveTerminationRunner extends DefaultMavenProcessRunner {

        private final ProcessHandle observedDescendant;
        private final ProcessHandle currentDescendant;
        private final AtomicInteger observedCount = new AtomicInteger();
        private final AtomicBoolean descendantsReturned = new AtomicBoolean();

        private ActiveTerminationRunner(ProcessHandle observedDescendant,
                                        ProcessHandle currentDescendant) {
            this.observedDescendant = observedDescendant;
            this.currentDescendant = currentDescendant;
        }

        private Process createProcess() {
            Process process = mock(Process.class);
            AtomicBoolean rootAlive = new AtomicBoolean(true);
            when(process.isAlive()).thenAnswer(invocation -> rootAlive.get());
            when(process.descendants()).thenAnswer(invocation -> {
                if (!rootAlive.get() || descendantsReturned.getAndSet(true)) {
                    return Stream.empty();
                }
                return Stream.of(observedDescendant);
            });
            when(process.destroyForcibly()).thenAnswer(invocation -> {
                rootAlive.set(false);
                return process;
            });
            when(process.pid()).thenReturn(5000L);
            return process;
        }

        @Override
        void descendantObserved(ProcessHandle descendant) {
            observedCount.incrementAndGet();
        }

        @Override
        Optional<ProcessHandle> findProcessHandle(long pid) {
            return Optional.of(currentDescendant);
        }

        @Override
        long processTerminationTimeoutNanos() {
            // 后代身份无法确认时不会终止，等待只需覆盖一个很短的绝对 deadline。
            return TimeUnit.MILLISECONDS.toNanos(20);
        }

        private int observedCount() {
            return observedCount.get();
        }
    }

    /**
     * 注入一个自然退出、且后代可被观测的 fake Process。
     * 通过包级 test seam 控制当前 PID 对应的 ProcessHandle 和清理预算，
     * 不依赖真实进程或固定 sleep。
     */
    private static final class IdentityRunner extends DefaultMavenProcessRunner {

        private final Path outputDir;
        private final int exitCode;
        private final List<ProcessHandle> descendants;
        private final Map<Long, ProcessHandle> currentHandles = new ConcurrentHashMap<>();
        private final AtomicInteger observedCount = new AtomicInteger();
        private final AtomicBoolean rootAlive = new AtomicBoolean(true);
        private final AtomicBoolean descendantsReturned = new AtomicBoolean();
        private final AtomicBoolean identityLookupFails = new AtomicBoolean();
        private final AtomicInteger identityLookups = new AtomicInteger();
        private long terminationNanos = TimeUnit.SECONDS.toNanos(2);
        private long gracefulNanos = TimeUnit.MILLISECONDS.toNanos(250);
        private Duration identityLookupDelay = Duration.ZERO;

        private IdentityRunner(Path outputDir, int exitCode, List<ProcessHandle> descendants) {
            this.outputDir = outputDir;
            this.exitCode = exitCode;
            this.descendants = descendants;
        }

        @Override
        Path createOutputTempFile(String prefix, String suffix) throws IOException {
            return Files.createTempFile(outputDir, prefix, suffix);
        }

        @Override
        void descendantObserved(ProcessHandle descendant) {
            observedCount.incrementAndGet();
        }

        @Override
        Optional<ProcessHandle> findProcessHandle(long pid) {
            identityLookups.incrementAndGet();
            // 每次身份查询按固定预算推进，便于稳定验证 deadline 到期后不再发起新查询。
            awaitAtLeast(identityLookupDelay);
            if (identityLookupFails.get()) {
                throw new IllegalStateException("injected identity lookup failure");
            }
            return Optional.ofNullable(currentHandles.get(pid));
        }

        @Override
        long processTerminationTimeoutNanos() {
            return terminationNanos;
        }

        @Override
        long descendantGracefulTerminationNanos() {
            return gracefulNanos;
        }

        @Override
        Process startProcess(ProcessBuilder pb) {
            Process process = mock(Process.class);
            when(process.isAlive()).thenAnswer(invocation -> rootAlive.get());
            when(process.descendants()).thenAnswer(invocation -> {
                if (!rootAlive.get() || descendantsReturned.getAndSet(true)) {
                    return Stream.empty();
                }
                return Stream.of(descendants.toArray(ProcessHandle[]::new));
            });
            try {
                when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
                    rootAlive.set(false);
                    return true;
                });
            } catch (InterruptedException e) {
                throw new AssertionError("配置 fake Process.waitFor 失败", e);
            }
            when(process.exitValue()).thenReturn(exitCode);
            when(process.pid()).thenReturn(4242L);
            return process;
        }

        private IdentityRunner withTerminationTimeout(Duration timeout) {
            this.terminationNanos = timeout.toNanos();
            return this;
        }

        private IdentityRunner withGracefulTimeout(Duration timeout) {
            this.gracefulNanos = timeout.toNanos();
            return this;
        }

        private IdentityRunner failIdentityLookup() {
            this.identityLookupFails.set(true);
            return this;
        }

        private IdentityRunner withIdentityLookupDelay(Duration delay) {
            this.identityLookupDelay = delay;
            return this;
        }

        private int identityLookups() {
            return identityLookups.get();
        }

        private void mapCurrentHandles(ProcessHandle... handles) {
            for (ProcessHandle handle : handles) {
                currentHandles.put(handle.pid(), handle);
            }
        }

        private int observedCount() {
            return observedCount.get();
        }
    }


    /**
     * 根进程在 waitFor 中自然退出，并可分别控制退出前后的后代枚举结果。
     * 用于验证自然退出后的最终快照、身份保护以及快照异常的隔离。
     */
    private static final class NaturalExitSnapshotRunner extends DefaultMavenProcessRunner {

        private final Path outputDir;
        private final int exitCode;
        private final Map<Long, ProcessHandle> currentHandles = new ConcurrentHashMap<>();
        private final AtomicInteger observedCount = new AtomicInteger();
        private final AtomicInteger aliveSnapshotCalls = new AtomicInteger();
        private final AtomicBoolean rootAlive = new AtomicBoolean(true);
        private Supplier<Stream<ProcessHandle>> aliveDescendants = Stream::empty;
        private Supplier<Stream<ProcessHandle>> exitedDescendants = Stream::empty;

        private NaturalExitSnapshotRunner(Path outputDir, int exitCode) {
            this.outputDir = outputDir;
            this.exitCode = exitCode;
        }

        private NaturalExitSnapshotRunner withAliveDescendants(ProcessHandle... handles) {
            this.aliveDescendants = () -> Stream.of(handles);
            return this;
        }

        private NaturalExitSnapshotRunner withExitedDescendants(ProcessHandle... handles) {
            this.exitedDescendants = () -> Stream.of(handles);
            return this;
        }

        private NaturalExitSnapshotRunner withExitedSnapshotFailure() {
            this.exitedDescendants = () -> {
                throw new IllegalStateException("injected descendant snapshot failure");
            };
            return this;
        }

        private NaturalExitSnapshotRunner mapCurrentHandles(ProcessHandle... handles) {
            for (ProcessHandle handle : handles) {
                currentHandles.put(handle.pid(), handle);
            }
            return this;
        }

        @Override
        Path createOutputTempFile(String prefix, String suffix) throws IOException {
            return Files.createTempFile(outputDir, prefix, suffix);
        }

        @Override
        void descendantObserved(ProcessHandle descendant) {
            observedCount.incrementAndGet();
        }

        @Override
        Optional<ProcessHandle> findProcessHandle(long pid) {
            return Optional.ofNullable(currentHandles.get(pid));
        }

        @Override
        Process startProcess(ProcessBuilder pb) {
            Process process = mock(Process.class);
            when(process.isAlive()).thenAnswer(invocation -> rootAlive.get());
            when(process.descendants()).thenAnswer(invocation -> {
                if (!rootAlive.get()) {
                    // 根进程退出后才可见的后代：只能由最终快照补充记录。
                    return exitedDescendants.get();
                }
                // 轮询阶段只返回一次存活后代快照，避免重复记录干扰断言。
                return aliveSnapshotCalls.getAndIncrement() == 0
                        ? aliveDescendants.get()
                        : Stream.empty();
            });
            try {
                when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
                    rootAlive.set(false);
                    return true;
                });
            } catch (InterruptedException e) {
                throw new AssertionError("配置 fake Process.waitFor 失败", e);
            }
            when(process.exitValue()).thenReturn(exitCode);
            when(process.pid()).thenReturn(4444L);
            return process;
        }

        private int observedCount() {
            return observedCount.get();
        }
    }


    /**
     * 在根进程自然退出后，于轮询阶段分别制造输出超限或大小检查失败，
     * 用于验证两种异常都不会跳过已观测后代清理，也不会改写真实退出码。
     */
    private enum PollingAnomaly {
        OUTPUT_LIMIT,
        SIZE_CHECK_FAILURE
    }

    private static final class PollingAnomalyRunner extends DefaultMavenProcessRunner {

        private final Path outputDir;
        private final PollingAnomaly anomaly;
        private final ProcessHandle descendant;
        private final int overflowBytes;
        private final AtomicInteger observedCount = new AtomicInteger();
        private final AtomicInteger stdoutChecks = new AtomicInteger();
        private final AtomicBoolean rootAlive = new AtomicBoolean(true);
        private final AtomicBoolean descendantsReturned = new AtomicBoolean();
        private volatile boolean descendantsAliveWhenOutputRead;

        private PollingAnomalyRunner(Path outputDir, PollingAnomaly anomaly,
                                     ProcessHandle descendant, int overflowBytes) {
            this.outputDir = outputDir;
            this.anomaly = anomaly;
            this.descendant = descendant;
            this.overflowBytes = overflowBytes;
        }

        @Override
        Path createOutputTempFile(String prefix, String suffix) throws IOException {
            return Files.createTempFile(outputDir, prefix, suffix);
        }

        @Override
        long readOutputFileSize(Path file) throws IOException {
            if (file.getFileName().toString().endsWith(".stdout")) {
                int call = stdoutChecks.incrementAndGet();
                if (call >= 2) {
                    if (anomaly == PollingAnomaly.OUTPUT_LIMIT) {
                        Files.write(file, new byte[overflowBytes]);
                    } else {
                        throw new IOException("injected size failure call " + call);
                    }
                }
            }
            return super.readOutputFileSize(file);
        }

        @Override
        void descendantObserved(ProcessHandle descendant) {
            observedCount.incrementAndGet();
        }

        @Override
        Optional<ProcessHandle> findProcessHandle(long pid) {
            return Optional.of(descendant);
        }

        @Override
        Process startProcess(ProcessBuilder pb) {
            Process process = mock(Process.class);
            when(process.isAlive()).thenAnswer(invocation -> rootAlive.get());
            when(process.descendants()).thenAnswer(invocation -> {
                if (!rootAlive.get() || descendantsReturned.getAndSet(true)) {
                    return Stream.empty();
                }
                return Stream.of(descendant);
            });
            try {
                when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
                    // 第一次轮询返回 false，但根进程已经自然退出；后续轮询仍返回 false，
                    // 让输出异常在轮询阶段而不是最终检查阶段被观察到。
                    rootAlive.set(false);
                    return false;
                });
            } catch (InterruptedException e) {
                throw new AssertionError("配置 fake Process.waitFor 失败", e);
            }
            when(process.exitValue()).thenReturn(7);
            when(process.pid()).thenReturn(4343L);
            return process;
        }

        @Override
        String readOutputFile(Path file, int maxLogLength) {
            if (descendant.isAlive()) {
                descendantsAliveWhenOutputRead = true;
            }
            return super.readOutputFile(file, maxLogLength);
        }

        private int observedCount() {
            return observedCount.get();
        }

        private boolean descendantsAliveWhenOutputRead() {
            return descendantsAliveWhenOutputRead;
        }
    }

    /**
     * 注入一个持续存活、可被终止的 fake Process，并按输出流/调用次数模拟 Files.size() 失败。
     * 这样无需真实文件系统故障，也能稳定覆盖连续检查失败、成功重置和自然退出竞态。
     */
    private static final class SizeFailureRunner extends DefaultMavenProcessRunner {

        private final Path outputDir;
        private final BiPredicate<String, Integer> failOnCheck;
        private final int exitCode;
        private final int normalExitAfterWaitCalls;
        private final int markDeadAfterWaitCalls;
        private final List<Path> createdFiles = new ArrayList<>();
        private final AtomicInteger stdoutChecks = new AtomicInteger();
        private final AtomicInteger stderrChecks = new AtomicInteger();
        private final AtomicInteger waitCalls = new AtomicInteger();
        private final AtomicInteger destroyCalls = new AtomicInteger();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private Duration firstWaitDelay = Duration.ZERO;

        private SizeFailureRunner(Path outputDir, BiPredicate<String, Integer> failOnCheck,
                                  int exitCode, int normalExitAfterWaitCalls,
                                  int markDeadAfterWaitCalls) {
            this.outputDir = outputDir;
            this.failOnCheck = failOnCheck;
            this.exitCode = exitCode;
            this.normalExitAfterWaitCalls = normalExitAfterWaitCalls;
            this.markDeadAfterWaitCalls = markDeadAfterWaitCalls;
        }

        private SizeFailureRunner delayFirstWait(Duration delay) {
            this.firstWaitDelay = delay;
            return this;
        }

        @Override
        Path createOutputTempFile(String prefix, String suffix) throws IOException {
            Path file = Files.createTempFile(outputDir, prefix, suffix);
            createdFiles.add(file);
            return file;
        }

        @Override
        long readOutputFileSize(Path file) throws IOException {
            String stream = file.getFileName().toString().endsWith(".stdout")
                    ? "stdout" : "stderr";
            int call = ("stdout".equals(stream) ? stdoutChecks : stderrChecks)
                    .incrementAndGet();
            if (failOnCheck.test(stream, call)) {
                throw new IOException("injected size failure " + stream + " call " + call);
            }
            return super.readOutputFileSize(file);
        }

        @Override
        Process startProcess(ProcessBuilder pb) {
            Process process = mock(Process.class);
            when(process.isAlive()).thenAnswer(invocation -> alive.get());
            when(process.descendants()).thenAnswer(invocation -> Stream.empty());
            when(process.exitValue()).thenReturn(exitCode);
            when(process.pid()).thenReturn(4242L);
            when(process.destroyForcibly()).thenAnswer(invocation -> {
                destroyCalls.incrementAndGet();
                alive.set(false);
                return process;
            });
            try {
                when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
                    int call = waitCalls.incrementAndGet();
                    if (call == 1 && !firstWaitDelay.isZero()) {
                        LockSupport.parkNanos(firstWaitDelay.toNanos());
                    }
                    if (markDeadAfterWaitCalls > 0 && call >= markDeadAfterWaitCalls) {
                        alive.set(false);
                        return false;
                    }
                    if (normalExitAfterWaitCalls > 0 && call >= normalExitAfterWaitCalls) {
                        alive.set(false);
                        return true;
                    }
                    return false;
                });
            } catch (InterruptedException e) {
                throw new AssertionError("配置 fake Process.waitFor 失败", e);
            }
            return process;
        }

        private List<Path> createdFiles() {
            return createdFiles;
        }

        private int destroyCalls() {
            return destroyCalls.get();
        }
    }

    /**
     * 注入一个在 waitFor 期间“退出”的进程：waitFor 返回前写入超限输出，
     * 用于稳定验证退出后最终检查会保留真实退出码而不是改写成 -1。
     */
    private static final class FinalCheckRunner extends DefaultMavenProcessRunner {

        private final Path outputDir;
        private final int exitCode;
        private final int outputBytes;
        private final List<Path> createdFiles = new ArrayList<>();

        private FinalCheckRunner(Path outputDir, int exitCode, int outputBytes) {
            this.outputDir = outputDir;
            this.exitCode = exitCode;
            this.outputBytes = outputBytes;
        }

        @Override
        Path createOutputTempFile(String prefix, String suffix) throws IOException {
            Path file = Files.createTempFile(outputDir, prefix, suffix);
            createdFiles.add(file);
            return file;
        }

        @Override
        Process startProcess(ProcessBuilder pb) {
            Process process = mock(Process.class);
            try {
                when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
                    // waitFor 返回前写入数据，模拟进程在轮询间隔内退出并留下超限输出。
                    Files.write(createdFiles.get(0), new byte[outputBytes]);
                    return true;
                });
            } catch (InterruptedException e) {
                throw new AssertionError("配置 fake Process.waitFor 失败", e);
            }
            when(process.exitValue()).thenReturn(exitCode);
            when(process.isAlive()).thenReturn(false);
            return process;
        }

        private List<Path> createdFiles() {
            return createdFiles;
        }
    }

}
