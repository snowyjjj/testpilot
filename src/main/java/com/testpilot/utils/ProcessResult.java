package com.testpilot.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 进程执行结果。
 */
@Getter
@AllArgsConstructor
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
}
