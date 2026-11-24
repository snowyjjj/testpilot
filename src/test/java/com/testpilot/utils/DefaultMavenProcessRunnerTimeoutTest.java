package com.testpilot.utils;

import com.testpilot.dto.TestExecutionResult;
import com.testpilot.service.ProjectService;
import com.testpilot.service.impl.TestExecutionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultMavenProcessRunner} 超时处理测试。
 * <p>
 * 使用真实假 Maven 脚本和嵌套 Java 辅助进程，验证超时结果、主进程退出、直接子进程退出、
 * 调用线程中断、后代持有输出通道和多个 Runner 并发执行。
 * <p>
 * T5/T5b/T6 验证 Runner 在中断和“父进程已退出、后代仍持有输出通道”时都能有界返回：
 * 执行线程中断标志必须恢复，清理等待不得因已有或重复中断忙循环，输出读取不得依赖无限期的管道 EOF。
 * 所有等待都有上限，所有已记录进程都会按 PID+启动时间校验后清理。
 * <p>
 * 假 Maven 脚本类型依赖 os.name，因此与修改该属性的测试共享系统属性资源锁。
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DefaultMavenProcessRunnerTimeoutTest {

    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READY_TIMEOUT = FakeMavenProcessSupport.READY_TIMEOUT;
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROCESS_EXIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration INTERRUPT_RUN_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HANG_OBSERVE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CONCURRENT_RUN_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_LOG_LENGTH = 10_000;
    private static final String PROJECT_ID = "p1";

    /** 单独验证进程树清理等待的最长有界观察时间。 */
    private static final Duration TREE_WAIT_DEADLINE = Duration.ofMillis(600);
    private static final Duration TREE_WAIT_REPEATED_DEADLINE = Duration.ofMillis(1_200);
    private static final Duration TREE_WAIT_UPPER_BOUND = Duration.ofSeconds(3);
    /** 忙循环时 CPU 时间会接近等待时长；正常 park 时远低于该阈值。 */
    private static final long TREE_WAIT_MAX_CPU_NANOS = TimeUnit.MILLISECONDS.toNanos(150);
    private static final long TEST_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    @TempDir
    Path tempDir;

    private Path workDir;
    private DefaultMavenProcessRunner runner;
    private final FakeMavenProcessSupport.ProcessRegistry processRegistry =
            new FakeMavenProcessSupport.ProcessRegistry();

    @BeforeEach
    void setUp() throws IOException {
        runner = new DefaultMavenProcessRunner();
        workDir = Files.createDirectories(tempDir.resolve("work"));
    }

    @AfterEach
    void tearDown() {
        try {
            trackRecordsSafely();
        } finally {
            terminateSafely();
        }
    }

    private void prepareFakeMaven() throws Exception {
        Path fakeMaven = FakeMavenProcessSupport.createFakeMaven(tempDir);
        ReflectionTestUtils.setField(runner, "configuredExecutable",
                fakeMaven.toAbsolutePath().toString());
    }

    private FakeMavenProcessSupport.ProcessRecord awaitHelperRecord() throws Exception {
        FakeMavenProcessSupport.awaitFile(tempDir.resolve("helper.ready"), READY_TIMEOUT);
        FakeMavenProcessSupport.ProcessRecord record =
                FakeMavenProcessSupport.awaitRecord(tempDir, "helper", Duration.ofSeconds(1));
        processRegistry.track(record);
        return record;
    }

    private ProcessResult awaitResult(AsyncCall<ProcessResult> call) throws Exception {
        return call.await(RESULT_TIMEOUT);
    }

    private void cleanup(AsyncCall<?> call) {
        if (call != null) {
            call.interrupt();
        }
        try {
            trackRecordsSafely();
        } finally {
            terminateSafely();
        }
        if (call != null && !call.awaitTermination(CLEANUP_TIMEOUT)) {
            System.err.println("[WARN] Runner 执行线程未在清理期限内退出，保留 daemon 线程以免阻塞测试 JVM");
        }
    }

    /**
     * 清理扫描是 best-effort；单个坏记录由 ProcessRegistry 内部告警并跳过。
     * 这里额外兜底运行时异常，保证 terminateAll() 仍有机会执行且不掩盖原始测试失败。
     */
    private void trackRecordsSafely() {
        try {
            processRegistry.trackRecords(tempDir);
        } catch (RuntimeException e) {
            System.err.println("[WARN] 扫描辅助进程记录失败: " + e);
        }
    }

    private void terminateSafely() {
        try {
            processRegistry.terminateAll();
        } catch (RuntimeException e) {
            System.err.println("[WARN] 清理辅助进程失败: " + e);
        }
    }

    // ============================== T1 / T2：简单超时与主进程退出 ==============================

    @Test
    void run_timeout_simpleProcess_returnsTimedOutResult() throws Exception {
        prepareFakeMaven();
        AsyncCall<ProcessResult> call = null;
        try {
            call = AsyncCall.start("timeout-simple",
                    () -> runner.run(workDir, new String[]{"hold"}, RUN_TIMEOUT, MAX_LOG_LENGTH));
            FakeMavenProcessSupport.ProcessRecord helper = awaitHelperRecord();

            ProcessResult result = awaitResult(call);

            assertTrue(result.isTimedOut(), "超时必须标记 timedOut=true");
            assertEquals(-1, result.getExitCode(), "超时退出码必须为 -1");
            assertTrue(FakeMavenProcessSupport.awaitProcessGone(helper, PROCESS_EXIT_TIMEOUT),
                    "超时后主辅助进程必须在期限内退出，pid=" + helper.pid());
        } finally {
            cleanup(call);
        }
    }

    // ============================== T3：直接子进程退出 ==============================

    @Test
    void run_timeout_directChildProcessEventuallyTerminates() throws Exception {
        prepareFakeMaven();
        AsyncCall<ProcessResult> call = null;
        try {
            call = AsyncCall.start("timeout-tree",
                    () -> runner.run(workDir, new String[]{"hold-tree"}, RUN_TIMEOUT, MAX_LOG_LENGTH));

            FakeMavenProcessSupport.ProcessRecord parent =
                    FakeMavenProcessSupport.awaitRecord(tempDir, "parent", READY_TIMEOUT);
            processRegistry.track(parent);
            FakeMavenProcessSupport.awaitFile(tempDir.resolve("child.ready"), READY_TIMEOUT);
            FakeMavenProcessSupport.ProcessRecord child =
                    FakeMavenProcessSupport.awaitRecord(tempDir, "child", READY_TIMEOUT);
            processRegistry.track(child);
            FakeMavenProcessSupport.awaitFile(tempDir.resolve("tree.ready"), READY_TIMEOUT);

            assertNotEquals(parent.pid(), child.pid(), "父子进程必须记录不同 PID");

            ProcessResult result = awaitResult(call);

            assertTrue(result.isTimedOut(), "进程树超时必须标记 timedOut=true");
            assertEquals(-1, result.getExitCode(), "进程树超时退出码必须为 -1");
            assertTrue(FakeMavenProcessSupport.awaitProcessGone(parent, PROCESS_EXIT_TIMEOUT),
                    "超时后父进程必须在期限内退出，pid=" + parent.pid());
            assertTrue(FakeMavenProcessSupport.awaitProcessGone(child, PROCESS_EXIT_TIMEOUT),
                    "超时后直接子进程必须在期限内退出，pid=" + child.pid());
        } finally {
            cleanup(call);
        }
    }

    // ============================== T4：服务层超时映射 ==============================

    @Test
    void executeTests_realRunnerTimeout_mapsToTimeoutStatus() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("project"));
        prepareFakeMaven();

        ProjectService projectService = mock(ProjectService.class);
        when(projectService.getMavenProjectDir(PROJECT_ID)).thenReturn(projectDir);
        TestExecutionServiceImpl service = new TestExecutionServiceImpl(
                projectService, runner, new ProjectExecutionLock());
        ReflectionTestUtils.setField(service, "timeoutSeconds", (int) RUN_TIMEOUT.toSeconds());
        ReflectionTestUtils.setField(service, "maxLogLength", MAX_LOG_LENGTH);
        ReflectionTestUtils.setField(service, "jacocoVersion", "0.8.11");

        AsyncCall<TestExecutionResult> call = null;
        try {
            call = AsyncCall.start("timeout-service",
                    () -> service.executeTests(PROJECT_ID));
            FakeMavenProcessSupport.ProcessRecord helper = awaitHelperRecord();

            TestExecutionResult result = call.await(RESULT_TIMEOUT);

            assertFalse(result.isSuccess(), "超时执行必须标记为失败");
            assertEquals("TIMEOUT", result.getStatus(), "timedOut=true 必须映射为 TIMEOUT");
            assertEquals(-1, result.getExitCode(), "服务层应保留超时退出码 -1");
            assertNotNull(result.getErrorMessage(), "超时必须提供错误信息");
            assertTrue(result.getErrorMessage().contains("超时"),
                    "超时错误信息应包含“超时”，实际: " + result.getErrorMessage());
            assertTrue(FakeMavenProcessSupport.awaitProcessGone(helper, PROCESS_EXIT_TIMEOUT),
                    "服务层超时后辅助进程必须在期限内退出，pid=" + helper.pid());
        } finally {
            cleanup(call);
        }
    }

    // ============================== T5：调用线程中断 ==============================

    @Test
    void run_threadInterrupt_returnsAndPreservesInterruptFlag() throws Exception {
        prepareFakeMaven();
        AtomicReference<Boolean> interruptedAfterRun = new AtomicReference<>();
        AsyncCall<ProcessResult> call = null;
        try {
            call = AsyncCall.start("runner-interrupt", () -> {
                ProcessResult result = runner.run(workDir, new String[]{"hold"},
                        INTERRUPT_RUN_TIMEOUT, MAX_LOG_LENGTH);
                interruptedAfterRun.set(Thread.currentThread().isInterrupted());
                return result;
            });
            FakeMavenProcessSupport.ProcessRecord helper = awaitHelperRecord();

            call.interrupt();
            ProcessResult result = call.await(HANG_OBSERVE_TIMEOUT);

            assertEquals(-1, result.getExitCode(), "中断后 Runner 应返回失败退出码 -1");
            assertFalse(result.isTimedOut(), "线程中断不应被误标记为超时");
            assertEquals(Boolean.TRUE, interruptedAfterRun.get(),
                    "Runner 返回后执行线程的中断标志必须保留");
            assertTrue(FakeMavenProcessSupport.awaitProcessGone(helper, PROCESS_EXIT_TIMEOUT),
                    "中断后 fake Maven 必须在期限内退出，pid=" + helper.pid());
        } finally {
            cleanup(call);
        }
    }

    // ============================== T5b：清理等待中的中断安全性 ==============================

    @Test
    void waitForTreeExit_alreadyInterrupted_doesNotBusyLoopAndRestoresFlag() {
        Process process = alwaysAliveProcess();
        long deadline = System.nanoTime() + TREE_WAIT_DEADLINE.toNanos();

        Thread.currentThread().interrupt();
        long cpuStart = currentThreadCpuNanos();
        long start = System.nanoTime();
        boolean exited;
        boolean flagAfterWait;
        long cpuUsed;
        try {
            exited = invokeWaitForTreeExit(process, deadline);
            flagAfterWait = Thread.currentThread().isInterrupted();
            cpuUsed = elapsedThreadCpuNanos(cpuStart);
        } finally {
            // 测试线程的中断标志不能泄漏到其他测试。
            Thread.interrupted();
        }
        long elapsed = System.nanoTime() - start;

        assertFalse(exited, "持续存活的进程不应被视为已退出");
        assertTrue(flagAfterWait, "等待结束后必须保留进入等待前已有的中断标志");
        assertTrue(elapsed >= TREE_WAIT_DEADLINE.toNanos() / 2,
                "已有中断时仍应执行有界等待，实际耗时: " + elapsed + "ns");
        assertTrue(elapsed < TREE_WAIT_UPPER_BOUND.toNanos(),
                "等待不得超出 deadline 过多，实际耗时: " + elapsed + "ns");
        assertTrue(cpuUsed < 0 || cpuUsed < TREE_WAIT_MAX_CPU_NANOS,
                "已有中断时不得忙循环，等待期间 CPU 时间: " + cpuUsed + "ns");
    }

    @Test
    void waitForTreeExit_interruptDuringWait_keepsWaitingAndRestoresFlag() throws Exception {
        Process process = alwaysAliveProcess();
        long deadline = System.nanoTime() + TREE_WAIT_DEADLINE.toNanos();
        TreeWaitProbe probe = new TreeWaitProbe(runner, process, deadline,
                "tree-wait-during-interrupt");

        long start = System.nanoTime();
        probe.start();
        try {
            probe.awaitParked();
            probe.interrupt();
            probe.awaitFinished(TREE_WAIT_UPPER_BOUND);
        } finally {
            probe.ensureStopped();
        }
        long elapsed = System.nanoTime() - start;

        probe.assertNoFailure();
        assertFalse(probe.exited(), "进程仍存活时等待应返回 false");
        assertTrue(probe.interruptedAfterWait(), "等待期间发生的中断必须在返回前恢复");
        assertTrue(elapsed >= TREE_WAIT_DEADLINE.toNanos() / 2,
                "等待期间收到中断后仍应继续执原有有界等待，实际耗时: " + elapsed + "ns");
        assertTrue(elapsed < TREE_WAIT_UPPER_BOUND.toNanos(),
                "中断不得延长 deadline，实际耗时: " + elapsed + "ns");
        assertTrue(probe.cpuUsedNanos() < 0
                        || probe.cpuUsedNanos() < TREE_WAIT_MAX_CPU_NANOS,
                "等待期间收到中断后不得忙循环，CPU 时间: "
                        + probe.cpuUsedNanos() + "ns");
    }

    @Test
    void waitForTreeExit_repeatedInterrupts_doNotExtendDeadlineAndRestoreFlag() throws Exception {
        Process process = alwaysAliveProcess();
        long deadline = System.nanoTime() + TREE_WAIT_REPEATED_DEADLINE.toNanos();
        TreeWaitProbe probe = new TreeWaitProbe(runner, process, deadline,
                "tree-wait-repeated-interrupt");

        long start = System.nanoTime();
        probe.start();
        try {
            probe.awaitParked();
            for (int i = 0; i < 3; i++) {
                probe.interrupt();
                probe.awaitParked();
            }
            probe.awaitFinished(TREE_WAIT_UPPER_BOUND);
        } finally {
            probe.ensureStopped();
        }
        long elapsed = System.nanoTime() - start;

        probe.assertNoFailure();
        assertFalse(probe.exited(), "进程仍存活时等待应返回 false");
        assertTrue(probe.interruptedAfterWait(), "重复中断后仍必须恢复中断标志");
        assertTrue(elapsed >= TREE_WAIT_REPEATED_DEADLINE.toNanos() / 2,
                "重复中断不应让等待提前结束，实际耗时: " + elapsed + "ns");
        assertTrue(elapsed < TREE_WAIT_UPPER_BOUND.toNanos(),
                "重复中断不得无限延长 deadline，实际耗时: " + elapsed + "ns");
        assertTrue(probe.cpuUsedNanos() < 0
                        || probe.cpuUsedNanos() < TREE_WAIT_MAX_CPU_NANOS,
                "重复中断不得导致忙循环，CPU 时间: " + probe.cpuUsedNanos() + "ns");
    }

    // ============================== T6：正常退出但后代持有管道 ==============================

    @Test
    void run_parentExitsWhileChildHoldsPipe_shouldReturnWithinBoundedObservation() throws Exception {
        prepareFakeMaven();
        AsyncCall<ProcessResult> call = null;
        try {
            call = AsyncCall.start("runner-pipe-holder",
                    () -> runner.run(workDir, new String[]{"exit-with-inherited-child"},
                            CONCURRENT_RUN_TIMEOUT, MAX_LOG_LENGTH));

            FakeMavenProcessSupport.ProcessRecord exiter =
                    FakeMavenProcessSupport.awaitRecord(tempDir, "exiter", READY_TIMEOUT);
            processRegistry.track(exiter);
            FakeMavenProcessSupport.awaitFile(tempDir.resolve("child.ready"), READY_TIMEOUT);
            FakeMavenProcessSupport.ProcessRecord child =
                    FakeMavenProcessSupport.awaitRecord(tempDir, "child", READY_TIMEOUT);
            processRegistry.track(child);
            FakeMavenProcessSupport.awaitFile(tempDir.resolve("exiter.ready"), READY_TIMEOUT);
            assertNotEquals(exiter.pid(), child.pid(), "父进程和子进程必须记录不同 PID");

            // 父进程已经正常退出，但子进程仍持有继承的 stdout/stderr 管道。
            ProcessResult result = call.await(HANG_OBSERVE_TIMEOUT);

            assertEquals(0, result.getExitCode(), "父进程正常退出码应为 0");
            assertFalse(result.isTimedOut(), "父进程正常退出不应标记超时");
        } finally {
            cleanup(call);
        }
    }

    // ============================== T7：多个 Runner 并发执行 ==============================

    @Test
    void run_fourConcurrentRunners_keepOutputsIsolatedAndTerminate() throws Exception {
        List<AsyncCall<ProcessResult>> calls = new ArrayList<>();
        List<FakeMavenProcessSupport.ProcessRecord> helpers = new ArrayList<>();
        List<Path> runDirs = new ArrayList<>();
        List<String> tags = List.of("r0", "r1", "r2", "r3");
        try {
            Path concurrentRoot = Files.createDirectories(tempDir.resolve("concurrent"));
            for (String tag : tags) {
                // 每个 Runner 使用独立脚本目录，避免并发写同一个 script-path.txt 时，
                // Windows cmd 的文件占用错误被写入 stderr，造成无关的输出串扰。
                Path runDir = Files.createDirectories(concurrentRoot.resolve(tag));
                runDirs.add(runDir);
                Path fakeMaven = FakeMavenProcessSupport.createFakeMaven(runDir);
                DefaultMavenProcessRunner concurrentRunner = new DefaultMavenProcessRunner();
                ReflectionTestUtils.setField(concurrentRunner, "configuredExecutable",
                        fakeMaven.toAbsolutePath().toString());
                calls.add(AsyncCall.start("runner-concurrent-" + tag,
                        () -> concurrentRunner.run(workDir,
                                new String[]{"tagged-hold", tag},
                                CONCURRENT_RUN_TIMEOUT, MAX_LOG_LENGTH)));
            }

            for (int i = 0; i < tags.size(); i++) {
                String tag = tags.get(i);
                Path runDir = runDirs.get(i);
                FakeMavenProcessSupport.awaitFile(
                        runDir.resolve("hold-" + tag + ".ready"), READY_TIMEOUT);
                FakeMavenProcessSupport.ProcessRecord helper =
                        FakeMavenProcessSupport.awaitRecord(runDir, "hold-" + tag,
                                Duration.ofSeconds(1));
                processRegistry.track(helper);
                helpers.add(helper);
                assertTrue(ProcessHandle.of(helper.pid())
                                .filter(helper::matches)
                                .map(ProcessHandle::isAlive)
                                .orElse(false),
                        "并发 helper 必须仍存活，pid=" + helper.pid());
            }

            for (int i = 0; i < tags.size(); i++) {
                String tag = tags.get(i);
                Files.writeString(runDirs.get(i).resolve("release-" + tag + ".txt"),
                        "release\n", java.nio.charset.StandardCharsets.UTF_8);
            }

            for (int i = 0; i < tags.size(); i++) {
                String tag = tags.get(i);
                ProcessResult result = calls.get(i).await(RESULT_TIMEOUT);
                assertEquals(0, result.getExitCode(), "并发执行应正常退出: " + tag);
                assertFalse(result.isTimedOut(), "并发执行不应超时: " + tag);
                assertEquals("STDOUT-" + tag + "\n", result.getStdout(),
                        "stdout 不得与其他 Runner 串扰: " + tag);
                assertEquals("STDERR-" + tag + "\n", result.getStderr(),
                        "stderr 不得与其他 Runner 串扰: " + tag);
            }

            for (int i = 0; i < tags.size(); i++) {
                assertTrue(FakeMavenProcessSupport.awaitProcessGone(
                                helpers.get(i), PROCESS_EXIT_TIMEOUT),
                        "并发 helper 必须最终退出: " + tags.get(i));
            }
        } finally {
            for (AsyncCall<ProcessResult> call : calls) {
                call.interrupt();
            }
            try {
                processRegistry.trackRecords(tempDir);
                for (Path runDir : runDirs) {
                    processRegistry.trackRecords(runDir);
                }
            } finally {
                terminateSafely();
            }
            for (AsyncCall<ProcessResult> call : calls) {
                if (!call.awaitTermination(CLEANUP_TIMEOUT)) {
                    System.err.println("[WARN] 并发 Runner 线程未在清理期限内退出");
                }
            }
        }
    }

    // ============================== T8：连续执行与线程清理 ==============================

    @Test
    void run_repeatedExecutions_keepOutputsIndependentAndLeaveNoReaderThreads() throws Exception {
        prepareFakeMaven();
        for (int i = 0; i < 3; i++) {
            AsyncCall<ProcessResult> call = null;
            try {
                call = AsyncCall.start("runner-repeat-" + i,
                        () -> runner.run(workDir, new String[]{"output"},
                                RUN_TIMEOUT, MAX_LOG_LENGTH));
                ProcessResult result = awaitResult(call);

                assertEquals(0, result.getExitCode(), "连续执行应正常退出: " + i);
                assertFalse(result.isTimedOut(), "连续执行不应超时: " + i);
                assertEquals("STDOUT-LINE\n", result.getStdout(),
                        "连续执行 stdout 必须独立: " + i);
                assertEquals("STDERR-LINE\n", result.getStderr(),
                        "连续执行 stderr 必须独立: " + i);
            } finally {
                cleanup(call);
            }
        }

        assertTrue(Thread.getAllStackTraces().keySet().stream()
                        .filter(Thread::isAlive)
                        .noneMatch(thread -> thread.getName().startsWith("testpilot-maven-")),
                "多次执行后不应遗留 Runner 输出读取线程");
    }

    /**
     * 用持续存活的 mock 进程隔离 waitForTreeExit() 的中断等待行为，
     * 不创建真实子进程，也不需要真实 Maven。
     */
    private Process alwaysAliveProcess() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        return process;
    }

    private boolean invokeWaitForTreeExit(Process process, long deadlineNanos) {
        Boolean result = ReflectionTestUtils.invokeMethod(runner, "waitForTreeExit",
                process, List.of(), deadlineNanos);
        assertNotNull(result, "waitForTreeExit 必须返回布尔结果");
        return result;
    }

    /**
     * 有界等待目标线程进入 park 状态。测试只依赖线程状态和 LockSupport，
     * 不使用固定长 sleep 作为唯一同步手段。
     */
    private static void awaitThreadParked(Thread thread, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            if (!thread.isAlive()) {
                throw new AssertionError("等待线程已提前退出: " + thread.getName());
            }
            LockSupport.parkNanos(TEST_POLL_NANOS);
        }
        throw new AssertionError("等待线程未进入 park 状态: " + thread.getName()
                + ", state=" + thread.getState());
    }

    private static long currentThreadCpuNanos() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported()
                ? bean.getCurrentThreadCpuTime()
                : -1L;
    }

    private static long elapsedThreadCpuNanos(long startNanos) {
        long endNanos = currentThreadCpuNanos();
        return startNanos < 0 || endNanos < 0 ? -1L : endNanos - startNanos;
    }

    /**
     * 在 daemon 线程中执行 waitForTreeExit()，记录返回值、中断标志和线程 CPU 时间。
     */
    private static final class TreeWaitProbe {

        private final Thread thread;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<Boolean> exited = new AtomicReference<>();
        private final AtomicReference<Boolean> interruptedAfterWait = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicLong cpuUsedNanos = new AtomicLong(-1L);

        private TreeWaitProbe(DefaultMavenProcessRunner runner, Process process,
                              long deadlineNanos, String name) {
            thread = new Thread(() -> {
                long cpuStart = currentThreadCpuNanos();
                started.countDown();
                try {
                    Boolean result = ReflectionTestUtils.invokeMethod(
                            runner, "waitForTreeExit", process, List.of(), deadlineNanos);
                    exited.set(result);
                    interruptedAfterWait.set(Thread.currentThread().isInterrupted());
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    long cpuEnd = currentThreadCpuNanos();
                    if (cpuStart >= 0 && cpuEnd >= 0) {
                        cpuUsedNanos.set(cpuEnd - cpuStart);
                    }
                    finished.countDown();
                }
            }, name);
            thread.setDaemon(true);
        }

        private void start() throws InterruptedException {
            thread.start();
            if (!started.await(TREE_WAIT_UPPER_BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("等待线程未启动: " + thread.getName());
            }
        }

        private void awaitParked() {
            awaitThreadParked(thread, TREE_WAIT_UPPER_BOUND);
        }

        private void interrupt() {
            thread.interrupt();
        }

        private void awaitFinished(Duration timeout) throws InterruptedException {
            if (!finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("等待线程未在 " + timeout + " 内结束");
            }
        }

        private void assertNoFailure() {
            assertNull(failure.get(), () -> "等待线程抛出异常: " + failure.get());
        }

        private boolean exited() {
            return Boolean.TRUE.equals(exited.get());
        }

        private boolean interruptedAfterWait() {
            return Boolean.TRUE.equals(interruptedAfterWait.get());
        }

        private long cpuUsedNanos() {
            return cpuUsedNanos.get();
        }

        private void ensureStopped() throws InterruptedException {
            if (thread.isAlive()) {
                thread.interrupt();
            }
            thread.join(CLEANUP_TIMEOUT.toMillis());
            if (thread.isAlive()) {
                throw new AssertionError("等待线程未在清理期限内退出: " + thread.getName());
            }
        }
    }

    // ============================== 有界异步调用 ==============================

    /**
     * daemon 工作线程，保证当前实现若卡在流读取也不会阻止测试 JVM 退出。
     */
    private static final class AsyncCall<T> {

        private final Thread thread;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<T> result = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private AsyncCall(String name, Supplier<T> action) {
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

        static <T> AsyncCall<T> start(String name, Supplier<T> action) {
            return new AsyncCall<>(name, action);
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
}

