package com.testpilot.utils;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Maven 进程执行器。抽象为接口以便在测试中替换为 Mock 实现。
 */
public interface MavenProcessRunner {

    /**
     * 在指定工作目录执行 Maven 命令。
     *
     * @param workingDir   工作目录（Maven 项目根目录）
     * @param args         Maven 命令参数列表（如 ["test"]），不含可执行文件名
     * @param timeout      执行超时
     * @param maxLogLength 标准输出/错误最大保留字符数，超出截断
     * @return 执行结果
     */
    ProcessResult run(Path workingDir, String[] args, Duration timeout, int maxLogLength);
}
