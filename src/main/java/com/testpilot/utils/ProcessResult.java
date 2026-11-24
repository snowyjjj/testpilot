package com.testpilot.utils;

import lombok.Getter;

/**
 * 进程执行结果。
 */
@Getter
public class ProcessResult {

    /** 进程退出码，超时时为 -1 */
    private final int exitCode;

    /** 标准输出（已按 maxLogLength 截断） */
    private final String stdout;

    /** 标准错误（已按 maxLogLength 截断） */
    private final String stderr;

    /** 是否因超时被强制终止 */
    private final boolean timedOut;

    /** 执行耗时（毫秒） */
    private final long executionTimeMs;

    /** 是否检测到 stdout 或 stderr 临时文件超过配置上限 */
    private final boolean outputLimitExceeded;

    /**
     * 兼容旧调用方：未提供输出上限标记时默认为 false。
     */
    public ProcessResult(int exitCode, String stdout, String stderr,
                         boolean timedOut, long executionTimeMs) {
        this(exitCode, stdout, stderr, timedOut, executionTimeMs, false);
    }

    public ProcessResult(int exitCode, String stdout, String stderr,
                         boolean timedOut, long executionTimeMs,
                         boolean outputLimitExceeded) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.timedOut = timedOut;
        this.executionTimeMs = executionTimeMs;
        this.outputLimitExceeded = outputLimitExceeded;
    }
}
