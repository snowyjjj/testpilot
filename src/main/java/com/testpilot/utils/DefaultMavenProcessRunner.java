package com.testpilot.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 ProcessBuilder 的 Maven 进程执行器。
 * <p>
 * 安全说明：本实现直接在宿主机执行 Maven 命令，适用于本地 Demo。
 * 运行不可信用户项目时，应进一步使用容器隔离和资源限制（CPU/内存/网络）。
 */
@Component
public class DefaultMavenProcessRunner implements MavenProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultMavenProcessRunner.class);

    @Value("${testpilot.maven.executable:}")
    private String configuredExecutable;

    @Override
    public ProcessResult run(Path workingDir, String[] args, Duration timeout, int maxLogLength) {
        String executable = resolveExecutable();
        List<String> command = new ArrayList<>();
        command.add(executable);
        // 统一使用 -B 批处理模式，避免交互式输出干扰日志解析
        command.add("-B");
        command.addAll(List.of(args));

        long start = System.currentTimeMillis();
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(false);
            process = pb.start();
        } catch (IOException e) {
            log.error("启动 Maven 进程失败, executable={}", executable, e);
            long elapsed = System.currentTimeMillis() - start;
            return new ProcessResult(-1, "",
                    "无法启动 Maven: " + e.getMessage() + "（请确认已安装 Maven 并配置到 PATH）",
                    false, elapsed);
        }

        // 双线程并发消费 stdout 和 stderr，防止缓冲区阻塞导致进程挂起
        CompletableFuture<String> stdoutFuture = CompletableFuture
                .supplyAsync(() -> readStream(process.getInputStream(), maxLogLength));
        CompletableFuture<String> stderrFuture = CompletableFuture
                .supplyAsync(() -> readStream(process.getErrorStream(), maxLogLength));

        boolean timedOut = false;
        int exitCode;
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                // 超时：强制终止进程树
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                // 等待进程真正退出，避免流读取线程残留
                process.waitFor(5, TimeUnit.SECONDS);
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            exitCode = -1;
        }

        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();
        long elapsed = System.currentTimeMillis() - start;

        log.info("Maven 执行完成, exitCode={}, timedOut={}, elapsedMs={}", exitCode, timedOut, elapsed);
        return new ProcessResult(exitCode, stdout, stderr, timedOut, elapsed);
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
     * 读取输入流并按最大长度截断。使用 try-with-resources 确保流关闭。
     */
    private String readStream(InputStream is, int maxLogLength) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() + line.length() + 1 > maxLogLength) {
                    sb.append(line, 0, Math.max(0, maxLogLength - sb.length()));
                    sb.append("\n... (output truncated)");
                    break;
                }
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            sb.append("[读取输出流失败: ").append(e.getMessage()).append(']');
        }
        return sb.toString();
    }
}
