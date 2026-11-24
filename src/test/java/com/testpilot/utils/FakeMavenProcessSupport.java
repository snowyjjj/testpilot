package com.testpilot.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * 真实子进程测试的共享支持。
 * <p>
 * 该支持类只依赖 JDK，负责生成当前平台的假 Maven 脚本、启动嵌套 Java 辅助进程，
 * 以及记录和清理辅助进程 PID/启动时间。测试本身负责断言，支持类不启动真实 Maven，
 * 不使用外部 taskkill/wmic 等工具，也不使用 {@code start} 创建脱离进程树的进程。
 */
final class FakeMavenProcessSupport {

    static final String RECORD_DIR_ENV = "TESTPILOT_RECORD_DIR";
    static final Duration READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HELPER_WAIT = Duration.ofSeconds(10);
    private static final Duration HELPER_MAX_WAIT = Duration.ofSeconds(60);
    private static final Duration READY_HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(25);

    private FakeMavenProcessSupport() {
    }

    /**
     * 在当前临时目录生成假 Maven：Windows 为 .cmd，Linux/macOS 为 .sh。
     * 脚本只负责把 Runner 追加了 -B 的完整参数转交给 Java 辅助进程。
     */
    static Path createFakeMaven(Path tempDir) throws Exception {
        boolean windows = isWindows();
        Path script = tempDir.resolve(windows ? "fake-mvn.cmd" : "fake-mvn.sh");
        Path testClasses = Path.of(FakeMavenProcessSupport.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                windows ? "java.exe" : "java");
        String helperClass = FakeMavenMain.class.getName();

        String content;
        if (windows) {
            content = "@echo off\r\n"
                    + "set \"" + RECORD_DIR_ENV + "=%~dp0\"\r\n"
                    + "> \"%~dp0script-path.txt\" echo \"%~f0\"\r\n"
                    + "\"" + javaExecutable + "\" -cp \"" + testClasses + "\" "
                    + helperClass + " %*\r\n"
                    + "exit /b %ERRORLEVEL%\r\n";
        } else {
            content = "#!/bin/sh\n"
                    + RECORD_DIR_ENV + "=\"$(dirname \"$0\")\"\n"
                    + "export " + RECORD_DIR_ENV + "\n"
                    + "printf '%s\\n' \"$0\" > \"$" + RECORD_DIR_ENV + "/script-path.txt\"\n"
                    + "exec \"" + javaExecutable + "\" -cp \"" + testClasses + "\" '"
                    + helperClass + "' \"$@\"\n";
        }

        Files.writeString(script, content, StandardCharsets.UTF_8);
        if (!windows) {
            Files.setPosixFilePermissions(script,
                    PosixFilePermissions.fromString("rwx------"));
        }
        return script;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 有上限地等待文件出现。用 LockSupport 轮询，不使用 Thread.sleep()。
     */
    static void awaitFile(Path file, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(file)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("等待文件时线程被中断: " + file);
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("等待文件超时: " + file);
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    /**
     * 等待并读取辅助进程记录。
     */
    static ProcessRecord awaitRecord(Path recordDir, String name, Duration timeout)
            throws Exception {
        Path recordFile = recordDir.resolve(name + ".record");
        awaitFile(recordFile, timeout);
        return readRecord(recordFile);
    }

    private static ProcessRecord readRecord(Path recordFile) throws IOException {
        List<String> lines = Files.readAllLines(recordFile, StandardCharsets.UTF_8);
        if (lines.isEmpty() || lines.get(0).isBlank()) {
            throw new IOException("进程记录为空: " + recordFile);
        }
        try {
            long pid = Long.parseLong(lines.get(0).trim());
            long startEpochMillis = lines.size() > 1 && !lines.get(1).isBlank()
                    ? Long.parseLong(lines.get(1).trim())
                    : -1L;
            return new ProcessRecord(pid, startEpochMillis);
        } catch (NumberFormatException e) {
            throw new IOException("进程记录格式错误: " + recordFile, e);
        }
    }

    /**
     * 在固定期限内轮询进程是否已经退出。
     * <p>
     * 只有在启动时间可确认时才把 PID 复用视为旧进程已退出；启动时间不可读时继续等待，
     * 绝不因为启动时间不可读而把仍存活的进程误判为已退出。
     */
    static boolean awaitProcessGone(ProcessRecord record, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProcessHandle> handle = ProcessHandle.of(record.pid());
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return true;
            }
            ProcessHandle process = handle.get();
            if (record.startEpochMillis() >= 0
                    && process.info().startInstant().isPresent()
                    && !record.matches(process)) {
                // PID 已被复用，记录中的旧进程确实已经退出。
                return true;
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
        return false;
    }

    /**
     * 进程 PID 与启动时间记录。启动时间未知时以 -1 表示。
     */
    record ProcessRecord(long pid, long startEpochMillis) {

        /**
         * 只有 PID 和启动时间都能确认一致时才匹配。
         * 启动时间未知时不允许退化为 PID-only 匹配。
         */
        boolean matches(ProcessHandle handle) {
            if (handle.pid() != pid || startEpochMillis < 0) {
                return false;
            }
            return handle.info().startInstant()
                    .map(start -> start.toEpochMilli() == startEpochMillis)
                    .orElse(false);
        }
    }

    /**
     * 测试级进程注册表。测试必须在 finally 中调用 {@link #terminateAll()}。
     */
    static final class ProcessRegistry {

        private final List<ProcessRecord> records = new CopyOnWriteArrayList<>();

        void track(ProcessRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        /**
         * 扫描当前测试临时目录中的进程记录，纳入 finally 清理。
         * 这是 best-effort 路径：单个空、格式错误或无法读取的记录只输出警告，
         * 不会中断其他记录的扫描，也不会让清理流程提前退出。
         */
        void trackRecords(Path recordDir) {
            if (!Files.isDirectory(recordDir)) {
                return;
            }
            try (Stream<Path> files = Files.list(recordDir)) {
                List<Path> recordFiles = files
                        .filter(path -> path.getFileName().toString().endsWith(".record"))
                        .toList();
                for (Path path : recordFiles) {
                    try {
                        track(readRecord(path));
                    } catch (IOException | RuntimeException e) {
                        System.err.println("[WARN] 无法读取辅助进程记录: "
                                + path + " - " + e);
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[WARN] 无法扫描辅助进程记录目录: "
                        + recordDir + " - " + e);
            }
        }

        /**
         * 只清理已记录的进程及其后代；不会按 PID 全盘扫描或杀死无关注进程。
         * 清理前必须同时确认 PID 与启动时间，身份无法确认时只告警，绝不执行
         * PID-only 强制终止。
         */
        void terminateAll() {
            Set<ProcessRecord> all = new LinkedHashSet<>(records);

            // 即使测试在读取子进程记录前失败，也尝试把已记录根进程的后代纳入清理。
            for (ProcessRecord record : new ArrayList<>(all)) {
                Optional<ProcessHandle> root = ProcessHandle.of(record.pid());
                if (root.isEmpty()) {
                    continue;
                }
                if (!record.matches(root.get())) {
                    System.err.println("[WARN] 无法确认辅助进程身份，跳过后代扫描: " + record);
                    continue;
                }
                root.get().descendants().forEach(descendant -> {
                    long startEpochMillis = descendant.info().startInstant()
                            .map(Instant::toEpochMilli)
                            .orElse(-1L);
                    ProcessRecord descendantRecord =
                            new ProcessRecord(descendant.pid(), startEpochMillis);
                    if (startEpochMillis < 0) {
                        System.err.println("[WARN] 无法确认后代辅助进程启动时间，跳过终止: "
                                + descendantRecord);
                    } else {
                        all.add(descendantRecord);
                    }
                });
            }

            // 先杀后代，再杀根进程，尽量降低子进程被重新挂载后遗漏的概率。
            List<ProcessRecord> ordered = new ArrayList<>(all);
            for (int i = ordered.size() - 1; i >= 0; i--) {
                ProcessRecord record = ordered.get(i);
                Optional<ProcessHandle> handle = ProcessHandle.of(record.pid());
                if (handle.isEmpty()) {
                    continue;
                }
                if (!record.matches(handle.get())) {
                    System.err.println("[WARN] 无法确认辅助进程身份，跳过强制终止: " + record);
                    continue;
                }
                handle.get().destroyForcibly();
            }

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline
                    && all.stream().anyMatch(ProcessRegistry::isAlive)) {
                LockSupport.parkNanos(POLL_NANOS);
            }
            boolean allGone = all.stream().noneMatch(ProcessRegistry::isAlive);
            if (!allGone) {
                System.err.println("[WARN] 仍有辅助进程未能终止: " + all);
            }
            records.clear();
        }

        private static boolean isAlive(ProcessRecord record) {
            return ProcessHandle.of(record.pid())
                    .filter(record::matches)
                    .map(ProcessHandle::isAlive)
                    .orElse(false);
        }
    }

    /**
     * 由假 Maven 脚本启动的辅助进程，只依赖 JDK。
     * 参数契约：args[0] 必须是 Runner 追加的 -B，args[1] 才是业务模式。
     */
    public static final class FakeMavenMain {

        public static void main(String[] args) throws Exception {
            if (args.length < 2 || !"-B".equals(args[0])) {
                fail("EXPECTED_ARGS: -B <mode> [length], actual length=" + args.length);
            }

            String recordDirValue = System.getenv(RECORD_DIR_ENV);
            if (recordDirValue == null) {
                fail("MISSING_ENV: " + RECORD_DIR_ENV);
            }
            Path recordDir = Path.of(recordDirValue);
            String mode = args[1];

            switch (mode) {
                case "args" -> Files.writeString(recordDir.resolve("args.txt"),
                        String.join("\n", args) + "\n", StandardCharsets.UTF_8);
                case "cwd" -> Files.writeString(recordDir.resolve("cwd.txt"),
                        System.getProperty("user.dir"), StandardCharsets.UTF_8);
                case "output" -> {
                    System.out.print("STDOUT-LINE\n");
                    System.err.print("STDERR-LINE\n");
                }
                case "empty" -> {
                    // 故意不输出
                }
                case "nonzero" -> System.exit(7);
                case "exact" -> {
                    int length = parseLength(args, 2);
                    System.out.print("X".repeat(Math.max(0, length - 1)) + "\n");
                }
                case "truncate" -> {
                    int length = parseLength(args, 2);
                    System.out.print("X".repeat(length) + "\n");
                }
                case "truncate-both" -> {
                    int length = parseLength(args, 2);
                    System.out.print("X".repeat(length) + "\n");
                    System.err.print("Y".repeat(length) + "\n");
                }
                case "flood-stdout" -> floodAndHold(recordDir, "flood-stdout",
                        true, false, parseLength(args, 2));
                case "flood-stderr" -> floodAndHold(recordDir, "flood-stderr",
                        false, true, parseLength(args, 2));
                case "hold" -> hold(recordDir, "helper");
                case "test" -> hold(recordDir, "helper");
                case "hold-tree" -> holdTree(recordDir);
                case "child-hold" -> hold(recordDir, "child");
                case "child-hold-output" -> childHoldOutput(recordDir);
                case "exit-with-inherited-child" -> exitWithInheritedChild(recordDir);
                case "exit-with-inherited-child-after-release" ->
                        exitWithInheritedChildAfterRelease(recordDir);
                case "exit-with-child-tree-after-release" ->
                        exitWithChildTreeAfterRelease(recordDir);
                case "child-tree-hold" -> childTreeHold(recordDir);
                case "grandchild-hold" -> hold(recordDir, "grandchild");
                case "tagged-hold" -> taggedHold(recordDir, args);
                default -> fail("UNKNOWN_MODE: " + mode);
            }

            System.out.flush();
            System.err.flush();
            System.exit(0);
        }

        private static void hold(Path recordDir, String name) throws Exception {
            writeRecord(recordDir, name);
            blockAndSignalReady(recordDir, name);
        }

        /**
         * 先写入进程记录，再向指定输出流写入超限数据并保持运行，
         * 用于验证 Runner 在根进程仍存活时检测并终止输出超限。
         */
        private static void floodAndHold(Path recordDir, String name,
                                         boolean stdout, boolean stderr, int length)
                throws Exception {
            writeRecord(recordDir, name);
            if (stdout) {
                System.out.print("X".repeat(Math.max(0, length)));
                System.out.flush();
            }
            if (stderr) {
                System.err.print("Y".repeat(Math.max(0, length)));
                System.err.flush();
            }
            blockAndSignalReady(recordDir, name);
        }

        private static void holdTree(Path recordDir) throws Exception {
            writeRecord(recordDir, "parent");

            Process child = new ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp", System.getProperty("java.class.path"),
                    FakeMavenMain.class.getName(), "-B", "child-hold")
                    .inheritIO()
                    .start();

            // child.ready 由子进程在确认自己进入阻塞状态后创建。
            awaitFile(recordDir.resolve("child.ready"), HELPER_WAIT);
            blockAndSignalReady(recordDir, "tree");
        }

        /**
         * 父进程启动一个继承 stdout/stderr 的子进程，等子进程确认就绪后正常退出。
         * 子进程继续持有管道写入端，用于复现“父进程已退出但 Runner 仍在 join”的场景。
         */
        private static void exitWithInheritedChild(Path recordDir) throws Exception {
            writeRecord(recordDir, "exiter");

            Process child = new ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp", System.getProperty("java.class.path"),
                    FakeMavenMain.class.getName(), "-B", "child-hold")
                    .inheritIO()
                    .start();

            awaitFile(recordDir.resolve("child.ready"), HELPER_WAIT);
            Files.writeString(recordDir.resolve("exiter.ready"), "ready\n",
                    StandardCharsets.UTF_8);
        }

        /**
         * 父进程启动带输出的子进程，确认子进程已写入标记并进入阻塞后，等待测试释放再自然退出。
         * release 文件提供明确同步，使测试可以等到 Runner 已经观测到子进程后再允许父进程退出。
         */
        private static void exitWithInheritedChildAfterRelease(Path recordDir) throws Exception {
            writeRecord(recordDir, "exiter");

            Process child = new ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp", System.getProperty("java.class.path"),
                    FakeMavenMain.class.getName(), "-B", "child-hold-output")
                    .inheritIO()
                    .start();

            awaitFile(recordDir.resolve("child.ready"), HELPER_WAIT);
            Files.writeString(recordDir.resolve("exiter.ready"), "ready\n",
                    StandardCharsets.UTF_8);
            awaitFile(recordDir.resolve("release-exiter.txt"), HELPER_MAX_WAIT);
        }

        /**
         * 子进程先向继承的 stdout/stderr 写入可识别标记并刷新，再进入阻塞。
         */
        private static void childHoldOutput(Path recordDir) throws Exception {
            writeRecord(recordDir, "child");
            System.out.print("STDOUT-CHILD-MARKER\n");
            System.err.print("STDERR-CHILD-MARKER\n");
            System.out.flush();
            System.err.flush();
            blockAndSignalReady(recordDir, "child");
        }

        /**
         * 根进程启动子进程，子进程再启动孙进程；根进程等整棵树就绪后等待测试释放，
         * 用于验证 Runner 在根进程存活期间观测到的多级后代能被尽力清理。
         */
        private static void exitWithChildTreeAfterRelease(Path recordDir) throws Exception {
            writeRecord(recordDir, "parent-tree");

            Process child = new ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp", System.getProperty("java.class.path"),
                    FakeMavenMain.class.getName(), "-B", "child-tree-hold")
                    .inheritIO()
                    .start();

            awaitFile(recordDir.resolve("child-tree.ready"), HELPER_WAIT);
            Files.writeString(recordDir.resolve("parent-tree.ready"), "ready\n",
                    StandardCharsets.UTF_8);
            awaitFile(recordDir.resolve("release-tree.txt"), HELPER_MAX_WAIT);
        }

        private static void childTreeHold(Path recordDir) throws Exception {
            writeRecord(recordDir, "child-tree");

            Process grandchild = new ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp", System.getProperty("java.class.path"),
                    FakeMavenMain.class.getName(), "-B", "grandchild-hold")
                    .inheritIO()
                    .start();

            awaitFile(recordDir.resolve("grandchild.ready"), HELPER_WAIT);
            blockAndSignalReady(recordDir, "child-tree");
        }

        /**
         * 输出带唯一 tag 的 stdout/stderr，然后等待测试创建 release 文件。
         * 这样多个 Runner 可以在同一时间段内真实并发读取各自的管道。
         */
        private static void taggedHold(Path recordDir, String[] args) throws Exception {
            if (args.length <= 2 || args[2].isBlank()) {
                fail("MISSING_TAG");
            }
            String tag = args[2];
            String recordName = "hold-" + tag;
            String releaseName = "release-" + tag + ".txt";

            writeRecord(recordDir, recordName);
            System.out.print("STDOUT-" + tag + "\n");
            System.err.print("STDERR-" + tag + "\n");
            System.out.flush();
            System.err.flush();
            Files.writeString(recordDir.resolve(recordName + ".ready"), "ready\n",
                    StandardCharsets.UTF_8);

            awaitFile(recordDir.resolve(releaseName), HELPER_MAX_WAIT);
        }

        /**
         * 让独立的 daemon 线程先进入有上限的阻塞等待，确认其已进入阻塞状态后
         * 才写 ready 文件；主线程再等待同一上限，保证 helper 最长生命周期有界。
         */
        private static void blockAndSignalReady(Path recordDir, String name) throws Exception {
            CountDownLatch blockerFinished = new CountDownLatch(1);
            Thread blocker = new Thread(() -> {
                try {
                    blockerFinished.await(HELPER_MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "testpilot-helper-blocker-" + name);
            blocker.setDaemon(true);
            blocker.start();

            awaitBlocked(blocker, READY_HANDSHAKE_TIMEOUT, name);
            Files.writeString(recordDir.resolve(name + ".ready"), "ready\n",
                    StandardCharsets.UTF_8);

            // 主线程也保持有界等待，使 helper 在 Runner/Registry 未清理时自行退出。
            blockerFinished.await(HELPER_MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        private static void awaitBlocked(Thread thread, Duration timeout, String name)
                throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                Thread.State state = thread.getState();
                // 带超时阻塞在 JVM 中显示为 TIMED_WAITING；WAITING 也同样表示已进入阻塞。
                if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                    return;
                }
                if (!thread.isAlive()) {
                    throw new AssertionError("helper 阻塞线程提前结束: " + name);
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("等待 helper 进入阻塞状态时线程被中断: " + name);
                }
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("helper 未在 " + timeout
                            + " 内进入阻塞状态: " + name + ", state=" + state);
                }
                LockSupport.parkNanos(POLL_NANOS);
            }
        }

        private static Path javaExecutable() {
            return Path.of(System.getProperty("java.home"), "bin",
                    isWindows() ? "java.exe" : "java");
        }

        private static void writeRecord(Path recordDir, String name) throws IOException {
            long pid = ProcessHandle.current().pid();
            long startEpochMillis = ProcessHandle.current().info().startInstant()
                    .map(Instant::toEpochMilli)
                    .orElse(-1L);
            Path target = recordDir.resolve(name + ".record");
            Path temp = recordDir.resolve(name + ".record.tmp." + pid);
            Files.writeString(temp, pid + "\n" + startEpochMillis + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static int parseLength(String[] args, int index) {
            if (args.length <= index) {
                fail("MISSING_LENGTH_ARG at index " + index);
            }
            try {
                return Integer.parseInt(args[index]);
            } catch (NumberFormatException e) {
                fail("INVALID_LENGTH_ARG: " + args[index]);
                return 0;
            }
        }

        private static void fail(String message) {
            System.err.println(message);
            System.exit(2);
        }
    }

}
