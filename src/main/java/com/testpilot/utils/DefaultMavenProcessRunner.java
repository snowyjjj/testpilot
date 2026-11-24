package com.testpilot.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * 基于 ProcessBuilder 的 Maven 进程执行器。
 * <p>
 * 安全说明：本实现直接在宿主机执行 Maven 命令，适用于本地 Demo。
 * 运行不可信用户项目时，应进一步使用容器隔离和资源限制（CPU/内存/网络）。
 * <p>
 * stdout/stderr 重定向到独立临时文件，并在等待进程期间周期性检查文件大小；
 * 任一输出流超过配置上限时尽力终止进程树。
 * <p>
 * 根进程自然退出后，只清理本次执行期间已经观测到的后代，不按 PID 全盘扫描。
 * 后代身份不可确认（缺少启动时间、ProcessHandle.of 为空、启动时间不一致或查询异常）
 * 时跳过终止并记录 WARN，绝不退化为 PID-only 终止。清理 deadline 是 best-effort：
 * 无法硬实时约束单次 ProcessHandle 系统调用；身份校验与 destroy 之间的 TOCTOU
 * 窗口以及根进程退出前未及时观测到的新后代仍然存在。
 * <p>
 * 后代身份筛选、优雅终止、等待和最终只读统计共享同一个绝对 deadline；deadline 到期后
 * 剩余后代记录为“未检查”，既不算已退出也不算身份不可确认。根进程自然退出后会在清理前
 * 再做一次 best-effort 最终快照，只补充记录、不覆盖已有可靠身份，也不保证捕获全部后代。
 */
@Component
public class DefaultMavenProcessRunner implements MavenProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultMavenProcessRunner.class);

    /** 超时/中断时终止进程树的总上限。 */
    private static final long PROCESS_TERMINATION_TIMEOUT_MS = 2_000L;

    /** 正常退出后清理已观测后代时，先等待优雅终止的短时间预算。 */
    private static final long DESCENDANT_GRACEFUL_TERMINATION_MS = 250L;

    private static final long PROCESS_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(25);

    /** stdout/stderr 各自允许的最大文件字节数。 */
    private static final long DEFAULT_MAX_OUTPUT_BYTES = 10L * 1024 * 1024;

    /** 单个输出流连续大小检查失败达到该次数后，不再信任大小监控。 */
    private static final int OUTPUT_SIZE_CHECK_MAX_CONSECUTIVE_FAILURES = 3;

    /** 分块读取输出文件时使用的固定字符缓冲区大小。 */
    private static final int READ_BUFFER_SIZE = 8 * 1024;

    /** 输出超过 maxLogLength 时追加的固定标记。 */
    private static final String TRUNCATION_MARKER = "\n... (output truncated)";

    @Value("${testpilot.maven.executable:}")
    private String configuredExecutable;

    @Value("${testpilot.maven.max-output-bytes:10485760}")
    private long maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;

    @Override
    public ProcessResult run(Path workingDir, String[] args, Duration timeout, int maxLogLength) {
        String executable = resolveExecutable();
        List<String> command = new ArrayList<>();
        command.add(executable);
        // 统一使用 -B 批处理模式，避免交互式输出干扰日志解析
        command.add("-B");
        command.addAll(List.of(args));

        long start = System.currentTimeMillis();
        Path stdoutFile = null;
        Path stderrFile = null;
        Process process;
        try {
            // 重定向到独立临时文件，避免后代进程继承并持有管道写入端时，
            // 当前线程永久阻塞在 readLine() 或 Windows 的管道 close() 上。
            stdoutFile = createOutputTempFile("testpilot-maven-", ".stdout");
            stderrFile = createOutputTempFile("testpilot-maven-", ".stderr");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(false);
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());
            process = startProcess(pb);
        } catch (IOException e) {
            deleteOutputFiles(stdoutFile, stderrFile);
            log.error("启动 Maven 进程失败, executable={}", executable, e);
            long elapsed = System.currentTimeMillis() - start;
            return new ProcessResult(-1, "",
                    "无法启动 Maven: " + e.getMessage() + "（请确认已安装 Maven 并配置到 PATH）",
                    false, elapsed);
        } catch (RuntimeException e) {
            // 例如临时文件创建被 SecurityManager 拒绝等运行时异常：先清理已创建文件，
            // 再原样抛出，不把编程错误伪装成正常的启动失败结果。
            deleteOutputFiles(stdoutFile, stderrFile);
            throw e;
        }

        long outputByteLimit = resolveMaxOutputBytes();
        OutputSizeMonitor outputSizeMonitor =
                new OutputSizeMonitor(stdoutFile, stderrFile, outputByteLimit,
                        this::readOutputFileSize);
        // 跟踪状态只属于本次 run()，不同 Runner 之间不共享。
        Map<Long, ObservedProcess> observedDescendants = new LinkedHashMap<>();
        boolean timedOut = false;
        boolean interrupted = false;
        boolean outputLimitExceeded = false;
        boolean outputSizeCheckFailed = false;
        boolean finished = false;
        // 根进程是否在未被本 Runner 主动终止的情况下退出。该状态与输出异常标志分离：
        // 即使轮询阶段发现输出超限或大小检查失败，只要根进程已自然退出，仍应清理已观测后代。
        boolean rootExitedNaturally = false;
        int exitCode;
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                outputSizeMonitor.check();
                long remaining = deadline - System.nanoTime();
                if (outputSizeMonitor.isExceeded()) {
                    outputLimitExceeded = true;
                    // 如果超限与超时同时被观察到，保留 TIMEOUT 的更高优先级。
                    if (remaining <= 0 && process.isAlive()) {
                        timedOut = true;
                    }
                    break;
                }
                if (outputSizeMonitor.isCheckFailed()) {
                    outputSizeCheckFailed = true;
                    // 检查失败与超时同时出现时，TIMEOUT 仍然优先。
                    if (remaining <= 0 && process.isAlive()) {
                        timedOut = true;
                    }
                    break;
                }
                if (remaining <= 0) {
                    if (process.isAlive()) {
                        timedOut = true;
                    } else {
                        finished = true;
                        rootExitedNaturally = true;
                    }
                    break;
                }
                // 必须在根进程存活时周期性地记录后代；根退出后父子关系可能立即消失。
                trackObservedDescendants(process, observedDescendants);
                if (process.waitFor(Math.min(remaining, PROCESS_POLL_NANOS), TimeUnit.NANOSECONDS)) {
                    finished = true;
                    rootExitedNaturally = true;
                    break;
                }
                // 等待返回后再次快照，覆盖等待期间新创建的后代。
                trackObservedDescendants(process, observedDescendants);
                outputSizeMonitor.check();
                if (outputSizeMonitor.isExceeded()) {
                    outputLimitExceeded = true;
                    if (System.nanoTime() >= deadline && process.isAlive()) {
                        timedOut = true;
                    }
                    break;
                }
                if (outputSizeMonitor.isCheckFailed()) {
                    outputSizeCheckFailed = true;
                    if (System.nanoTime() >= deadline && process.isAlive()) {
                        timedOut = true;
                    }
                    break;
                }
            }

            if (timedOut) {
                exitCode = -1;
                if (process.isAlive()) {
                    terminateProcessTree(process);
                }
            } else if (outputLimitExceeded) {
                if (process.isAlive()) {
                    // 主动终止输出超限的根进程；退出码统一为 -1，且不标记为 TIMEOUT。
                    exitCode = -1;
                    terminateProcessTree(process);
                } else {
                    // 根进程已自然退出：保留真实退出码，不能改写成主动终止的 -1。
                    finished = true;
                    rootExitedNaturally = true;
                    exitCode = process.exitValue();
                }
            } else if (outputSizeCheckFailed) {
                if (process.isAlive()) {
                    // 没有证据证明文件实际超过上限，只按“大小不可可靠检查”终止；
                    // 不设置 outputLimitExceeded，避免把未知大小误报为超限。
                    exitCode = -1;
                    terminateProcessTree(process);
                } else {
                    // 根进程已经自然退出：保留真实退出码，不伪造主动终止。
                    finished = true;
                    rootExitedNaturally = true;
                    exitCode = process.exitValue();
                }
            } else if (finished) {
                exitCode = process.exitValue();
            } else {
                // 理论上仅在极端竞态下到达；按原超时语义处理仍存活的根进程。
                if (process.isAlive()) {
                    timedOut = true;
                    exitCode = -1;
                    terminateProcessTree(process);
                } else {
                    finished = true;
                    rootExitedNaturally = true;
                    exitCode = process.exitValue();
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            exitCode = -1;
            // 先完成有界清理，再在 finally 中恢复中断标志，避免清理流程被立即打断。
            terminateProcessTree(process);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        // 输出异常标志不等于根进程被主动终止：只有超时或线程中断路径会主动结束根进程。
        // 因此这里不能排除 outputLimitExceeded/outputSizeCheckFailed，否则会漏掉轮询阶段
        // 已经观察到根进程自然退出、但同时发现输出异常的场景。
        boolean cleanupObservedDescendants = rootExitedNaturally && !timedOut && !interrupted;

        if (process.isAlive()) {
            // 正常路径通常已退出；若终止流程仍未让根进程退出，这里沿用同一套有界终止逻辑，
            // 避免在读取/删除临时文件时留下仍在写入的根进程。
            terminateProcessTree(process);
        }

        if (cleanupObservedDescendants) {
            // 根进程已自然退出时，已有主动终止路径不再适用；这里只清理此前观测到的后代，
            // 不再次终止根进程，也不改写根进程的真实退出码。
            // 退出瞬间可能仍有此前未及时观测到的后代，因此进入清理前再补充一次快照。
            // 这只是 best-effort：父子关系可能已经消失，快照失败也只记录 WARN。
            snapshotDescendantsAfterNaturalExit(process, observedDescendants);
            if (!observedDescendants.isEmpty()) {
                try {
                    terminateObservedDescendants(observedDescendants.values());
                } catch (RuntimeException e) {
                    // 后代清理是尽力而为，任何意外都不能阻止后续输出读取和临时文件清理。
                    log.warn("清理 Maven 已观测后代进程时出现未预期异常，继续读取输出", e);
                }
            }
        }

        // 轮询间隔内进程可能已经自然退出并写入了超限输出，退出后必须再做一次最终检查。
        // 该检查位于已观测后代清理之后，避免后代继续写入时过早读取或删除临时文件。
        outputSizeMonitor.check();
        if (outputSizeMonitor.isExceeded()) {
            outputLimitExceeded = true;
        }
        if (outputSizeMonitor.isCheckFailed()) {
            outputSizeCheckFailed = true;
        }
        if (outputLimitExceeded) {
            if (finished) {
                log.warn("Maven 进程已自然退出，但输出超过磁盘上限（每个输出流上限 {} 字节）: {}, exitCode={}",
                        outputByteLimit, outputSizeMonitor.describe(), exitCode);
            } else if (timedOut) {
                log.warn("Maven 执行超时，且输出超过磁盘上限（每个输出流上限 {} 字节）: {}, pid={}",
                        outputByteLimit, outputSizeMonitor.describe(), process.pid());
            } else if (interrupted) {
                log.warn("Maven 执行被中断，且输出超过磁盘上限（每个输出流上限 {} 字节）: {}, pid={}",
                        outputByteLimit, outputSizeMonitor.describe(), process.pid());
            } else {
                log.warn("Maven 输出超过磁盘上限（每个输出流上限 {} 字节），已终止进程树: {}, pid={}",
                        outputByteLimit, outputSizeMonitor.describe(), process.pid());
            }
        }
        if (outputSizeCheckFailed) {
            String failures = outputSizeMonitor.describeFailures();
            if (finished) {
                log.warn("Maven 进程已自然退出，但输出文件大小连续检查失败，保留真实退出码: {}, exitCode={}",
                        failures, exitCode);
            } else if (timedOut) {
                log.warn("Maven 执行超时，且输出文件大小连续检查失败（TIMEOUT 优先）: {}, pid={}",
                        failures, process.pid());
            } else if (interrupted) {
                log.warn("Maven 执行被中断，且输出文件大小连续检查失败: {}, pid={}",
                        failures, process.pid());
            } else {
                log.error("Maven 输出文件大小连续检查失败，已终止进程树: {}, pid={}",
                        failures, process.pid());
            }
        }

        String stdout;
        String stderr;
        try {
            stdout = readOutputFile(stdoutFile, maxLogLength);
            stderr = readOutputFile(stderrFile, maxLogLength);
        } finally {
            deleteOutputFiles(stdoutFile, stderrFile);
        }
        long elapsed = System.currentTimeMillis() - start;

        log.info("Maven 执行完成, exitCode={}, timedOut={}, outputLimitExceeded={}, elapsedMs={}",
                exitCode, timedOut, outputLimitExceeded, elapsed);
        return new ProcessResult(exitCode, stdout, stderr, timedOut, elapsed,
                outputLimitExceeded);
    }

    /**
     * 解析并校验每个输出流的上限。非正数配置回退到默认 10 MiB 并记录 WARN。
     * 包级可见是为了让测试能直接验证无效配置的回退行为。
     */
    long resolveMaxOutputBytes() {
        if (maxOutputBytes > 0) {
            return maxOutputBytes;
        }
        log.warn("testpilot.maven.max-output-bytes 配置无效({}), 使用默认值 {} 字节",
                maxOutputBytes, DEFAULT_MAX_OUTPUT_BYTES);
        return DEFAULT_MAX_OUTPUT_BYTES;
    }

    /**
     * 启动进程。默认直接调用 ProcessBuilder.start()；包级可见是为了让测试能够注入
     * 一个已自然退出的进程，从而稳定验证退出后的最终输出大小检查。
     */
    Process startProcess(ProcessBuilder pb) throws IOException {
        return pb.start();
    }

    /**
     * 读取单个输出临时文件的当前大小。
     * 包级可见是为了让测试注入 IOException，稳定验证连续检查失败保护。
     */
    long readOutputFileSize(Path file) throws IOException {
        return Files.size(file);
    }

    /**
     * 周期性读取 stdout/stderr 临时文件大小。两个输出流分别使用同一上限判断，
     * 不把二者大小相加。
     * <p>
     * 每个输出流独立记录连续失败次数；单次成功会清除该流的连续失败计数。
     * 连续失败达到 {@link #OUTPUT_SIZE_CHECK_MAX_CONSECUTIVE_FAILURES} 次后，
     * 该流被标记为不可可靠检查，由 Runner 决定是否终止仍存活的 Maven 进程。
     */
    private static final class OutputSizeMonitor {

        private final long maxBytes;
        private final StreamSizeState stdout;
        private final StreamSizeState stderr;

        private OutputSizeMonitor(Path stdoutFile, Path stderrFile, long maxBytes,
                                  OutputFileSizeReader sizeReader) {
            this.maxBytes = maxBytes;
            this.stdout = new StreamSizeState("stdout", stdoutFile, sizeReader);
            this.stderr = new StreamSizeState("stderr", stderrFile, sizeReader);
        }

        private void check() {
            stdout.check();
            stderr.check();
        }

        private boolean isExceeded() {
            return stdout.isExceeded(maxBytes) || stderr.isExceeded(maxBytes);
        }

        private boolean isCheckFailed() {
            return stdout.checkFailed || stderr.checkFailed;
        }

        private String describe() {
            return "stdout=" + stdout.describeSize() + ", stderr=" + stderr.describeSize();
        }

        private String describeFailures() {
            List<String> failures = new ArrayList<>();
            if (stdout.checkFailed) {
                failures.add(stdout.describeFailure());
            }
            if (stderr.checkFailed) {
                failures.add(stderr.describeFailure());
            }
            return String.join("; ", failures);
        }

        /**
         * 单个输出流的大小状态。失败计数与触发状态都只属于当前流，互不影响。
         */
        private static final class StreamSizeState {

            private final String name;
            private final Path file;
            private final OutputFileSizeReader sizeReader;

            private long bytes = -1;
            private int consecutiveFailures;
            private boolean checkFailed;
            private int failureCountAtTrip;
            private Throwable failureAtTrip;

            private StreamSizeState(String name, Path file, OutputFileSizeReader sizeReader) {
                this.name = name;
                this.file = file;
                this.sizeReader = sizeReader;
            }

            private void check() {
                try {
                    bytes = sizeReader.sizeOf(file);
                    // 一次成功检查清除连续失败计数；已经触发的失败状态保留，
                    // 以免最终检查成功后丢失“本次执行曾不可靠”的事实。
                    consecutiveFailures = 0;
                } catch (IOException | RuntimeException e) {
                    consecutiveFailures++;
                    if (checkFailed) {
                        log.warn("Maven {} 输出文件大小检查继续失败（连续第 {} 次）: {}, 异常: {}",
                                name, consecutiveFailures, file, e.toString());
                    } else if (consecutiveFailures >= OUTPUT_SIZE_CHECK_MAX_CONSECUTIVE_FAILURES) {
                        checkFailed = true;
                        failureCountAtTrip = consecutiveFailures;
                        failureAtTrip = e;
                        log.error("Maven {} 输出文件大小连续检查失败达到阈值（{} 次），不再信任大小监控: {}, 异常: {}",
                                name, consecutiveFailures, file, e.toString());
                    } else {
                        log.warn("检查 Maven {} 输出文件大小失败（连续第 {} 次）: {}, 异常: {}",
                                name, consecutiveFailures, file, e.toString());
                    }
                }
            }

            private boolean isExceeded(long maxBytes) {
                return bytes >= 0 && bytes > maxBytes;
            }

            private String describeSize() {
                return bytes < 0 ? "未知" : bytes + " 字节";
            }

            private String describeFailure() {
                return name + " 连续失败 " + failureCountAtTrip + " 次，最后异常: " + failureAtTrip;
            }
        }
    }

    /**
     * 可注入的文件大小读取接缝，便于测试模拟 Files.size() 异常。
     */
    @FunctionalInterface
    private interface OutputFileSizeReader {
        long sizeOf(Path file) throws IOException;
    }

    /**
     * 进程结束后从独立临时文件读取输出，并按 maxLogLength 保持原有截断语义。
     * 读取文件不会等待管道 EOF，因此后代是否仍持有文件句柄都不会阻塞 Runner。
     * <p>
     * 包级可见是为了让测试能够注入读取失败，验证临时文件清理路径。
     */
    String readOutputFile(Path file, int maxLogLength) {
        if (file == null) {
            return "";
        }
        try (InputStream in = Files.newInputStream(file)) {
            return readStream(in, maxLogLength);
        } catch (IOException e) {
            return "[读取输出流失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 创建 stdout/stderr 临时文件。包级可见是为了让测试能够注入创建失败。
     */
    Path createOutputTempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(prefix, suffix);
    }

    /**
     * 依次清理两个临时文件。任何单个文件的清理异常都不能阻止另一个文件的清理，
     * 也不能覆盖调用方正在传播的执行/读取异常。
     */
    private void deleteOutputFiles(Path stdoutFile, Path stderrFile) {
        try {
            deleteOutputFile(stdoutFile);
        } catch (Exception e) {
            log.warn("清理 stdout 临时文件时出现异常: {}", stdoutFile, e);
        } finally {
            try {
                deleteOutputFile(stderrFile);
            } catch (Exception e) {
                log.warn("清理 stderr 临时文件时出现异常: {}", stderrFile, e);
            }
        }
    }

    /**
     * 尽力删除单个临时文件。包级可见是为了让测试能够注入清理失败。
     */
    void deleteOutputFile(Path file) {
        if (file == null) {
            return;
        }
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException | RuntimeException e) {
                lastFailure = e;
                // Windows 在进程刚退出时可能短暂保留文件句柄；做有限重试，不无限等待。
                if (attempt < 2) {
                    LockSupport.parkNanos(PROCESS_POLL_NANOS * 2);
                }
            }
        }
        // 后代进程仍持有文件句柄时 Windows 可能暂时无法删除；不阻塞返回，
        // 由 JVM 退出或测试清理兜底删除，避免输出清理反过来制造新的等待。
        try {
            file.toFile().deleteOnExit();
        } catch (RuntimeException e) {
            log.warn("登记 Maven 输出临时文件退出时删除失败: {}", file, e);
        }
        log.warn("删除 Maven 输出临时文件失败，已登记退出时删除: {}", file, lastFailure);
    }

    /**
     * 在根进程仍存活时记录当前可见的后代。
     * <p>
     * 必须在根进程退出前周期调用；根进程退出后原来的父子关系可能立即消失，
     * 此时再调用 {@link Process#descendants()} 无法发现仍存活的旧后代。
     */
    private void trackObservedDescendants(Process process,
                                          Map<Long, ObservedProcess> observedDescendants) {
        try {
            if (!process.isAlive()) {
                return;
            }
            try (Stream<ProcessHandle> descendants = process.descendants()) {
                if (descendants != null) {
                    descendants.forEach(handle ->
                            recordObservedDescendant(handle, observedDescendants));
                }
            }
        } catch (RuntimeException e) {
            // 枚举失败不能影响主执行流程；已记录的后代仍可在自然退出后清理。
            log.warn("枚举 Maven 后代进程失败，稍后继续尝试: {}", e.toString());
        }
    }

    /**
     * 按 PID 去重记录后代身份。只有 PID 相同但启动时间不同才视为新进程，
     * 避免 PID 复用后继续把旧身份当作当前进程。
     */
    private void recordObservedDescendant(ProcessHandle handle,
                                          Map<Long, ObservedProcess> observedDescendants) {
        try {
            long pid = handle.pid();
            Instant startInstant = handle.info().startInstant().orElse(null);
            ObservedProcess existing = observedDescendants.get(pid);
            if (existing != null && existing.sameStartInstant(startInstant)) {
                return;
            }
            // 已经保存了可靠启动时间时，不被一次缺少启动时间的重复观测覆盖。
            if (existing != null && existing.hasStartInstant() && startInstant == null) {
                return;
            }

            ObservedProcess observed = new ObservedProcess(pid, startInstant, handle);
            observedDescendants.put(pid, observed);
            try {
                descendantObserved(handle);
            } catch (RuntimeException e) {
                // 测试同步钩子不能影响生产执行。
                log.warn("Maven 后代观测回调执行失败: pid={}", pid, e);
            }
        } catch (RuntimeException e) {
            log.warn("读取 Maven 后代进程身份失败，跳过本次记录: {}", e.toString());
        }
    }

    /**
     * 观测到新的后代进程时调用。默认空实现；包级可见是为了让测试能够使用明确的
     * 同步信号确认 Runner 已观测到指定进程，而不依赖固定毫秒数推断。
     */
    void descendantObserved(ProcessHandle descendant) {
        // 生产默认无操作。
    }

    /**
     * 根进程确认自然退出后补充一次最终后代快照。
     * <p>
     * 退出瞬间可能仍有尚未记录的后代（例如轮询间隔内创建），因此在进入自然退出清理前
     * 再枚举一次。该快照只能补充记录：{@link #recordObservedDescendant(ProcessHandle, Map)}
     * 仍按 PID + startInstant 去重，已保存的可靠身份不会被缺少启动时间的重复观测覆盖。
     * 根进程退出后父子关系可能已经消失，所以这是 best-effort，不能保证捕获全部后代，
     * 也不能捕获退出后新建或已经脱离父进程的进程。
     * <p>
     * 枚举或读取身份的异常都只记录 WARN，不影响根进程真实退出码和结果返回。
     */
    private void snapshotDescendantsAfterNaturalExit(
            Process process, Map<Long, ObservedProcess> observedDescendants) {
        try (Stream<ProcessHandle> descendants = process.descendants()) {
            if (descendants != null) {
                descendants.forEach(handle ->
                        recordObservedDescendant(handle, observedDescendants));
            }
        } catch (RuntimeException e) {
            log.warn("根进程自然退出后补充后代快照失败（不影响真实退出码），继续清理已记录后代: {}",
                    e.toString());
        }
    }

    /**
     * 已观测后代的身份。保存 PID、启动时间和当时的 ProcessHandle 引用；
     * 后续终止前必须重新按 PID 查询并校验启动时间，避免 PID 复用误杀无关进程。
     */
    private static final class ObservedProcess {

        private final long pid;
        private final Instant startInstant;
        private final ProcessHandle handle;

        private ObservedProcess(long pid, Instant startInstant, ProcessHandle handle) {
            this.pid = pid;
            this.startInstant = startInstant;
            this.handle = handle;
        }

        private boolean hasStartInstant() {
            return startInstant != null;
        }

        private boolean sameStartInstant(Instant other) {
            if (startInstant == null || other == null) {
                return startInstant == null && other == null;
            }
            return startInstant.equals(other);
        }

        @Override
        public String toString() {
            return pid + "@" + (startInstant == null ? "unknown" : startInstant);
        }
    }

    /**
     * 根进程自然退出后，尽力清理此前观测到且仍可确认存活的后代。
     * <p>
     * 只处理已经记录的身份，不按 PID 全盘扫描。身份不可确认（缺少启动时间、
     * {@link ProcessHandle#of(long)} 为空、启动时间不一致或查询异常）时跳过终止并记录 WARN，
     * 绝不退化为 PID-only 终止；不可确认也不能等同于已退出。
     * <p>
     * 每个后代的失败相互隔离。身份筛选、优雅终止、等待和强制终止共享同一个绝对 deadline，
     * 不会为每个后代重新获得完整预算。deadline 是 best-effort：无法硬实时约束单次
     * ProcessHandle 系统调用，只能保证在发起下一项终止操作前检查剩余预算。
     */
    private void terminateObservedDescendants(Collection<ObservedProcess> observedDescendants) {
        if (observedDescendants.isEmpty()) {
            return;
        }

        long timeoutNanos = processTerminationTimeoutNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        List<ObservedProcess> allObserved = new ArrayList<>(observedDescendants);
        // 身份筛选与后续终止共享同一个绝对 deadline；到期后剩余后代记为“未检查”，
        // 不能当作已退出，也不能继续发起身份查询。
        DescendantScan initialScan = scanObservedDescendants(allObserved, deadline, true);
        List<ObservedProcess> liveObserved = initialScan.alive();
        List<ObservedProcess> unverifiableObserved = initialScan.unverifiable();
        int uncheckedCount = initialScan.unchecked().size();
        if (uncheckedCount > 0) {
            log.warn("Maven 已观测后代身份检查未在 {}ms 内完成，{} 个后代未检查（未发起身份查询）: {}",
                    TimeUnit.NANOSECONDS.toMillis(timeoutNanos), uncheckedCount,
                    initialScan.uncheckedPids());
        }
        if (!unverifiableObserved.isEmpty()) {
            log.warn("Maven 已观测后代中有 {} 个进程身份无法确认，跳过终止: {}",
                    unverifiableObserved.size(), observedPids(unverifiableObserved));
        }
        if (liveObserved.isEmpty()) {
            if (unverifiableObserved.isEmpty() && uncheckedCount == 0) {
                log.info("Maven 根进程已自然退出，未发现仍可确认存活的已观测后代进程");
            } else {
                log.warn("Maven 根进程已自然退出，未发现仍可确认存活的后代，但存在 "
                                + "{} 个身份不可确认、{} 个未检查的已观测后代",
                        unverifiableObserved.size(), uncheckedCount);
            }
            return;
        }

        log.info("Maven 根进程已自然退出，开始清理 {} 个已观测后代进程", liveObserved.size());

        // 先尝试优雅终止，给 Java/脚本进程一个有限的自行退出机会。
        // 每次操作前独立按 PID + startInstant 重新校验；校验与 destroy 之间的 TOCTOU
        // 窗口仍由平台调度决定，不能声称已经彻底消除 PID 重用风险。
        terminateObservedDescendantsGracefully(liveObserved, deadline, timeoutNanos);

        long gracefulDeadline = Math.min(deadline,
                System.nanoTime() + descendantGracefulTerminationNanos());
        waitForObservedDescendantsExit(liveObserved, gracefulDeadline);

        // 优雅等待结束后，只对仍然存活且身份再次校验通过的后代强制终止。
        terminateObservedDescendantsForcibly(liveObserved, deadline, timeoutNanos);

        DescendantWaitOutcome waitOutcome = waitForObservedDescendantsExit(liveObserved, deadline);
        // deadline 到期后这里只做有界的只读身份统计，不再发起任何 destroy 或 destroyForcibly；
        // 预算耗尽时剩余后代记为未检查，不会被误报为已退出。
        DescendantScan finalScan = scanObservedDescendants(allObserved, deadline, true);
        boolean allConfirmedExited = waitOutcome == DescendantWaitOutcome.ALL_CONFIRMED_EXITED
                && finalScan.allConfirmedExited();
        if (allConfirmedExited) {
            log.info("Maven 已观测后代进程清理完成");
        } else {
            log.warn("Maven 已观测后代进程未全部确认退出: 已确认退出={}, 仍存活={}, 身份不可确认={}, "
                            + "未检查={}, 等待结果={}",
                    finalScan.exitedCount(), finalScan.alivePids(),
                    finalScan.unverifiablePids(), finalScan.uncheckedPids(), waitOutcome);
        }
    }

    private void terminateObservedDescendantsGracefully(List<ObservedProcess> descendants,
                                                        long deadline, long timeoutNanos) {
        for (int i = 0; i < descendants.size(); i++) {
            if (System.nanoTime() >= deadline) {
                log.warn("Maven 已观测后代优雅终止未在 {}ms 内全部发起，{} 个后代未处理",
                        TimeUnit.NANOSECONDS.toMillis(timeoutNanos), descendants.size() - i);
                return;
            }
            ObservedProcess observed = descendants.get(i);
            ObservedProcessResolution current = resolveObservedProcess(observed, true);
            if (!current.isAlive()) {
                continue;
            }
            try {
                current.handle().destroy();
            } catch (RuntimeException e) {
                log.warn("优雅终止 Maven 已观测后代进程失败，稍后尝试强制终止: {}", observed, e);
            }
        }
    }

    private void terminateObservedDescendantsForcibly(List<ObservedProcess> descendants,
                                                      long deadline, long timeoutNanos) {
        for (int i = 0; i < descendants.size(); i++) {
            if (System.nanoTime() >= deadline) {
                log.warn("Maven 已观测后代强制终止未在 {}ms 内全部发起，{} 个后代未处理",
                        TimeUnit.NANOSECONDS.toMillis(timeoutNanos), descendants.size() - i);
                return;
            }
            ObservedProcess observed = descendants.get(i);
            ObservedProcessResolution current = resolveObservedProcess(observed, true);
            if (!current.isAlive()) {
                continue;
            }
            try {
                current.handle().destroyForcibly();
            } catch (RuntimeException e) {
                log.warn("强制终止 Maven 已观测后代进程失败: {}", observed, e);
            }
        }
    }


    /**
     * 在绝对 deadline 内等待已观测后代退出，并保持已有中断标志。
     * <p>
     * 返回结果区分四种状态：全部确认退出、仍有进程确认存活、仍有进程身份不可确认、
     * 扫描预算耗尽导致仍有后代未检查。身份不可确认和未检查都不能当作已退出；
     * 若已没有确认存活的进程，立即返回，不再为无法确认或未检查的身份消耗完整预算。
     * <p>
     * 扫描本身也受 deadline 约束：到期后不再发起新的身份查询，避免等待阶段在 deadline
     * 之后继续无界遍历。与 {@link #waitForTreeExit(Process, List, long)} 一样，等待期间
     * 清除中断标志以避免忙循环，返回前恢复；重复中断不会延长 deadline。
     */
    private DescendantWaitOutcome waitForObservedDescendantsExit(
            Collection<ObservedProcess> descendants, long deadline) {
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                DescendantScan scan = scanObservedDescendants(descendants, deadline, false);
                if (!scan.uncheckedPids().isEmpty()) {
                    // 扫描预算已耗尽：剩余后代未检查，既不能视为已退出，也不能继续查询。
                    return DescendantWaitOutcome.UNCHECKED;
                }
                if (scan.alivePids().isEmpty()) {
                    return scan.unverifiablePids().isEmpty()
                            ? DescendantWaitOutcome.ALL_CONFIRMED_EXITED
                            : DescendantWaitOutcome.UNCONFIRMED;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return DescendantWaitOutcome.STILL_ALIVE;
                }
                LockSupport.parkNanos(Math.min(remaining, PROCESS_POLL_NANOS));
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 在统一绝对 deadline 内扫描已观测后代身份。
     * <p>
     * 每处理一个后代前检查剩余预算；deadline 到期后立即停止身份查询，剩余后代记录为
     * “未检查”。未检查既不是已退出，也不是身份不可确认，调用方不得把它当作清理完成。
     * 单个后代的身份查询异常由 {@link #resolveObservedProcess(ObservedProcess, boolean)}
     * 隔离为不可确认，不会中断其他后代的处理。
     * <p>
     * deadline 仍是 best-effort：只能保证在发起下一次身份查询前检查剩余预算，
     * 无法硬实时约束单次 ProcessHandle 系统调用。
     */
    private DescendantScan scanObservedDescendants(Collection<ObservedProcess> descendants,
                                                   long deadline, boolean warnIfUnverifiable) {
        List<ObservedProcess> snapshot = new ArrayList<>(descendants);
        List<ObservedProcess> alive = new ArrayList<>();
        List<ObservedProcess> unverifiable = new ArrayList<>();
        List<ObservedProcess> unchecked = new ArrayList<>();
        int exitedCount = 0;
        int index = 0;
        for (; index < snapshot.size(); index++) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            ObservedProcess observed = snapshot.get(index);
            ObservedProcessResolution resolution =
                    resolveObservedProcess(observed, warnIfUnverifiable);
            if (resolution.isAlive()) {
                alive.add(observed);
            } else if (resolution.isUnverifiable()) {
                unverifiable.add(observed);
            } else {
                exitedCount++;
            }
        }
        if (index < snapshot.size()) {
            // 只遍历内存中已记录的身份并登记为未检查，不再发起任何身份查询。
            unchecked.addAll(snapshot.subList(index, snapshot.size()));
        }
        return new DescendantScan(alive, unverifiable, unchecked, exitedCount);
    }

    private static List<Long> observedPids(Collection<ObservedProcess> descendants) {
        List<Long> pids = new ArrayList<>(descendants.size());
        for (ObservedProcess observed : descendants) {
            pids.add(observed.pid);
        }
        return pids;
    }

    /**
     * 重新解析并校验已观测后代身份，返回三态结果：
     * 已确认退出、身份匹配且仍存活、身份不可确认。
     * <p>
     * ProcessHandle.of 为空、启动时间缺失、查询异常或启动时间不一致都属于身份不可确认；
     * 不可确认时绝不退化为只按 PID 终止。
     */
    private ObservedProcessResolution resolveObservedProcess(ObservedProcess observed,
                                                              boolean warnIfUnverifiable) {
        try {
            if (!observed.hasStartInstant()) {
                if (warnIfUnverifiable) {
                    log.warn("无法确认 Maven 已观测后代进程身份（缺少启动时间），跳过终止: {}",
                            observed);
                }
                return ObservedProcessResolution.unverifiable();
            }

            Optional<ProcessHandle> current = findProcessHandle(observed.pid);
            if (current.isEmpty()) {
                if (warnIfUnverifiable) {
                    log.warn("无法确认 Maven 已观测后代进程身份"
                                    + "（ProcessHandle.of 返回空），跳过终止: {}",
                            observed);
                }
                return ObservedProcessResolution.unverifiable();
            }
            ProcessHandle handle = current.get();
            if (!handle.isAlive()) {
                return ObservedProcessResolution.exited();
            }
            Instant currentStart = handle.info().startInstant().orElse(null);
            if (currentStart == null) {
                if (warnIfUnverifiable) {
                    log.warn("无法确认 Maven 已观测后代进程身份（当前启动时间不可用），跳过终止: {}",
                            observed);
                }
                return ObservedProcessResolution.unverifiable();
            }
            if (!observed.sameStartInstant(currentStart)) {
                if (warnIfUnverifiable) {
                    log.warn("无法确认 Maven 已观测后代进程身份"
                                    + "（PID 对应的启动时间不一致，可能已复用），跳过终止: {}",
                            observed);
                }
                return ObservedProcessResolution.unverifiable();
            }
            return ObservedProcessResolution.alive(handle);
        } catch (RuntimeException e) {
            if (warnIfUnverifiable) {
                log.warn("校验 Maven 已观测后代进程身份失败，跳过终止: {}", observed, e);
            }
            return ObservedProcessResolution.unverifiable();
        }
    }

    /**
     * 按 PID 查询当前进程句柄。包级可见是为了让测试能够稳定注入
     * ProcessHandle.of 为空、PID 复用和查询异常，而无需静态 mock JDK 方法。
     */
    Optional<ProcessHandle> findProcessHandle(long pid) {
        return ProcessHandle.of(pid);
    }

    /**
     * 终止流程的总预算。包级可见是为了让测试能够注入极短 deadline，
     * 验证到期后不再发起新的后代终止操作。
     */
    long processTerminationTimeoutNanos() {
        return TimeUnit.MILLISECONDS.toNanos(PROCESS_TERMINATION_TIMEOUT_MS);
    }

    /**
     * 优雅终止后等待后代自行退出的短预算。仍受总 deadline 限制。
     */
    long descendantGracefulTerminationNanos() {
        return TimeUnit.MILLISECONDS.toNanos(DESCENDANT_GRACEFUL_TERMINATION_MS);
    }

    private enum ObservedProcessState {
        EXITED,
        ALIVE,
        UNVERIFIABLE
    }

    private enum DescendantWaitOutcome {
        ALL_CONFIRMED_EXITED,
        /** 仍确认有后代存活，直到 deadline 到期。 */
        STILL_ALIVE,
        /** 已无确认存活的后代，但仍有身份不可确认的后代。 */
        UNCONFIRMED,
        /** 扫描预算耗尽，仍有后代未检查。 */
        UNCHECKED
    }

    private static final class ObservedProcessResolution {

        private final ObservedProcessState state;
        private final ProcessHandle handle;

        private ObservedProcessResolution(ObservedProcessState state, ProcessHandle handle) {
            this.state = state;
            this.handle = handle;
        }

        private static ObservedProcessResolution exited() {
            return new ObservedProcessResolution(ObservedProcessState.EXITED, null);
        }

        private static ObservedProcessResolution alive(ProcessHandle handle) {
            return new ObservedProcessResolution(ObservedProcessState.ALIVE, handle);
        }

        private static ObservedProcessResolution unverifiable() {
            return new ObservedProcessResolution(ObservedProcessState.UNVERIFIABLE, null);
        }

        private boolean isAlive() {
            return state == ObservedProcessState.ALIVE;
        }

        private boolean isUnverifiable() {
            return state == ObservedProcessState.UNVERIFIABLE;
        }

        private ProcessHandle handle() {
            return handle;
        }
    }

    /**
     * 一次后代扫描的结果：已确认退出计数、确认存活、身份不可确认，以及因 deadline
     * 到期而未检查。未检查既不能算已退出，也不能算身份不可确认。
     */
    private static final class DescendantScan {

        private final List<ObservedProcess> alive;
        private final List<ObservedProcess> unverifiable;
        private final List<ObservedProcess> unchecked;
        private final int exitedCount;

        private DescendantScan(List<ObservedProcess> alive, List<ObservedProcess> unverifiable,
                               List<ObservedProcess> unchecked, int exitedCount) {
            this.alive = alive;
            this.unverifiable = unverifiable;
            this.unchecked = unchecked;
            this.exitedCount = exitedCount;
        }

        private List<ObservedProcess> alive() {
            return alive;
        }

        private List<ObservedProcess> unverifiable() {
            return unverifiable;
        }

        private List<ObservedProcess> unchecked() {
            return unchecked;
        }

        private List<Long> alivePids() {
            return observedPids(alive);
        }

        private List<Long> unverifiablePids() {
            return observedPids(unverifiable);
        }

        private List<Long> uncheckedPids() {
            return observedPids(unchecked);
        }

        private int exitedCount() {
            return exitedCount;
        }

        /** 只有不存在存活、不可确认和未检查时才允许声称全部确认退出。 */
        private boolean allConfirmedExited() {
            return alive.isEmpty() && unverifiable.isEmpty() && unchecked.isEmpty();
        }
    }

    /**
     * 强制终止根进程和已发现的全部后代。
     * <p>
     * 进程可能在终止过程中继续创建后代，因此做有限轮重新快照；一旦根进程已经退出，
     * ProcessHandle 将无法再枚举新的后代，只能清理已记录的后代。所有等待都有上限。
     * <p>
     * 后代终止前必须按 PID 重新解析并校验 startInstant；仅持有旧 ProcessHandle
     * 或 PID 不足以执行 destroyForcibly。身份不可确认时跳过并记录 WARN。
     * 校验与终止之间仍存在平台相关 TOCTOU 窗口，不能声称彻底消除 PID 重用风险。
     * 根 Process 对象的既有管理语义保持不变。
     */
    private void terminateProcessTree(Process process) {
        Map<Long, ObservedProcess> knownDescendants = new LinkedHashMap<>();
        long timeoutNanos = processTerminationTimeoutNanos();
        long deadline = System.nanoTime() + timeoutNanos;

        for (int round = 0; round < 3 && System.nanoTime() < deadline; round++) {
            trackObservedDescendants(process, knownDescendants);
            forciblyTerminateKnownDescendants(knownDescendants.values(), deadline, timeoutNanos);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            if (waitForTreeExit(process, knownHandles(knownDescendants.values()), deadline)) {
                return;
            }
        }

        // 最后一轮再尝试一次，避免快照和终止之间的新后代被遗漏。
        forciblyTerminateKnownDescendants(knownDescendants.values(), deadline, timeoutNanos);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        if (!waitForTreeExit(process, knownHandles(knownDescendants.values()), deadline)) {
            log.warn("Maven 进程树未在 {}ms 内完全退出, pid={}",
                    TimeUnit.NANOSECONDS.toMillis(timeoutNanos), process.pid());
        }
    }

    private void forciblyTerminateKnownDescendants(Collection<ObservedProcess> descendants,
                                                   long deadline, long timeoutNanos) {
        List<ObservedProcess> snapshot = new ArrayList<>(descendants);
        for (int i = 0; i < snapshot.size(); i++) {
            if (System.nanoTime() >= deadline) {
                log.warn("Maven 后代进程强制终止未在 {}ms 内全部发起，{} 个后代未处理",
                        TimeUnit.NANOSECONDS.toMillis(timeoutNanos), snapshot.size() - i);
                return;
            }
            ObservedProcess observed = snapshot.get(i);
            ObservedProcessResolution current = resolveObservedProcess(observed, true);
            if (!current.isAlive()) {
                continue;
            }
            try {
                current.handle().destroyForcibly();
            } catch (RuntimeException e) {
                log.warn("强制终止 Maven 后代进程失败: {}", observed, e);
            }
        }
    }

    private List<ProcessHandle> knownHandles(Collection<ObservedProcess> descendants) {
        List<ProcessHandle> handles = new ArrayList<>(descendants.size());
        for (ObservedProcess observed : descendants) {
            handles.add(observed.handle);
        }
        return handles;
    }

    private boolean waitForTreeExit(Process process, List<ProcessHandle> descendants, long deadline) {
        // 进入等待前暂存并清除已有中断：否则 parkNanos() 会因线程已中断而立即返回，
        // 在进程仍存活时于 deadline 内形成忙循环。
        boolean interrupted = Thread.interrupted();
        try {
            while (System.nanoTime() < deadline) {
                if (!process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive)) {
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    // 使用剩余时间而不是固定整段轮询时间，重复中断也不会延长绝对 deadline。
                    LockSupport.parkNanos(Math.min(remaining, PROCESS_POLL_NANOS));
                }
                // parkNanos() 被中断时会立即返回但不清除标志；这里清除并记录，
                // 避免下一次 parkNanos() 继续立即返回，同时保证最终恢复中断状态。
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
            return !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 解析 Maven 可执行文件：优先使用配置，其次根据操作系统选择 mvn.cmd / mvn。
     */
    private String resolveExecutable() {
        if (configuredExecutable != null && !configuredExecutable.isBlank()) {
            return configuredExecutable;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "mvn.cmd";
        }
        return "mvn";
    }

    /**
     * 以固定大小字符缓冲区分块读取输入流，并按 maxLogLength 保持原有截断语义。
     * <p>
     * 该实现不调用 {@code BufferedReader.readLine()}，也不缓存完整单行或整份文件：
     * 内存上限为固定读取缓冲区加最多 maxLogLength 个字符的输出，截断标记长度为常量。
     */
    private String readStream(InputStream is, int maxLogLength) {
        OutputBuffer output = new OutputBuffer(maxLogLength);
        try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            char[] buffer = new char[READ_BUFFER_SIZE];
            boolean pendingCarriageReturn = false;
            int read;
            while (!output.isTruncated() && (read = reader.read(buffer)) != -1) {
                for (int i = 0; i < read && !output.isTruncated(); i++) {
                    char c = buffer[i];
                    if (pendingCarriageReturn) {
                        pendingCarriageReturn = false;
                        output.endLine();
                        if (output.isTruncated()) {
                            break;
                        }
                        // CRLF 只产生一次换行；单独的 CR 已在上一行结束。
                        if (c == '\n') {
                            continue;
                        }
                    }
                    if (c == '\r') {
                        pendingCarriageReturn = true;
                    } else if (c == '\n') {
                        output.endLine();
                    } else {
                        output.content(c);
                    }
                }
            }
            if (!output.isTruncated()) {
                if (pendingCarriageReturn) {
                    // 文件以单独 CR 结尾：CR 本身就是一个行结束符。
                    output.endLine();
                } else {
                    output.finish();
                }
            }
        } catch (IOException e) {
            output.appendError(e);
        }
        return output.result();
    }

    /**
     * 有界输出累加器，复刻旧 readLine() 版本的行结束符归一化和截断边界语义。
     */
    private static final class OutputBuffer {

        private final int maxLogLength;
        private final StringBuilder text;
        private boolean truncated;
        private boolean lineHasContent;
        private char pendingHighSurrogate;

        private OutputBuffer(int maxLogLength) {
            this.maxLogLength = maxLogLength;
            int initialCapacity = maxLogLength > 0
                    ? Math.min(maxLogLength, READ_BUFFER_SIZE)
                    : 0;
            this.text = new StringBuilder(initialCapacity);
        }

        private boolean isTruncated() {
            return truncated;
        }

        private void content(char c) {
            if (truncated) {
                return;
            }
            lineHasContent = true;

            if (pendingHighSurrogate != 0) {
                char high = pendingHighSurrogate;
                pendingHighSurrogate = 0;
                if (Character.isLowSurrogate(c)) {
                    appendSurrogatePair(high, c);
                    return;
                }
                appendSingle(high);
                if (truncated) {
                    return;
                }
            }

            if (Character.isHighSurrogate(c)) {
                // 暂存高代理项，等下一个字符确认是否能组成完整代理对。
                if (maxLogLength <= 0 || text.length() >= maxLogLength) {
                    truncated = true;
                } else {
                    pendingHighSurrogate = c;
                }
            } else {
                appendSingle(c);
            }
        }

        private void endLine() {
            if (truncated) {
                return;
            }
            flushPendingHighSurrogate();
            if (truncated) {
                return;
            }
            // 旧实现要求“行内容 + 一个换行符”能放入剩余空间，边界值不算截断。
            if (maxLogLength <= 0 || text.length() >= maxLogLength) {
                truncated = true;
                return;
            }
            text.append('\n');
            lineHasContent = false;
        }

        private void finish() {
            if (truncated) {
                return;
            }
            flushPendingHighSurrogate();
            if (!truncated && lineHasContent) {
                // 旧 readLine() 对文件末尾没有换行的非空行也会补一个换行。
                endLine();
            }
        }

        private void flushPendingHighSurrogate() {
            if (pendingHighSurrogate == 0) {
                return;
            }
            char high = pendingHighSurrogate;
            pendingHighSurrogate = 0;
            appendSingle(high);
        }

        private void appendSingle(char c) {
            if (maxLogLength <= 0 || text.length() >= maxLogLength) {
                truncated = true;
                return;
            }
            text.append(c);
        }

        private void appendSurrogatePair(char high, char low) {
            // 代理对必须整体写入，截断边界不能留下孤立的高/低代理字符。
            if (maxLogLength <= 0 || text.length() > maxLogLength - 2) {
                truncated = true;
                return;
            }
            text.append(high).append(low);
        }

        private void appendError(IOException e) {
            text.append("[读取输出流失败: ").append(e.getMessage()).append(']');
        }

        private String result() {
            if (truncated) {
                text.append(TRUNCATION_MARKER);
            }
            return text.toString();
        }
    }
}
