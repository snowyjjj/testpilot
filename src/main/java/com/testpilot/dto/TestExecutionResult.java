package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单元测试执行结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestExecutionResult implements Serializable {

    /** 项目 ID */
    private String projectId;

    /** 整体是否成功（Maven 退出码为 0 且无测试失败） */
    private boolean success;

    /**
     * 执行状态：
     * PASSED - 全部通过
     * FAILED - 测试断言失败
     * COMPILE_ERROR - 主源码或测试源码编译失败
     * MAVEN_ERROR - Maven 执行错误（非测试失败）
     * TIMEOUT - 执行超时
     * NO_REPORTS - 未找到 Surefire 报告
     */
    private String status;

    /** Maven 进程退出码，超时为 -1 */
    private int exitCode;

    private int totalTests;
    private int passedTests;
    private int failedTests;
    private int skippedTests;

    /** 执行耗时（毫秒） */
    private long executionTime;

    /** Maven 输出日志（已截断） */
    private String output;

    /** 错误信息（编译错误摘要等） */
    private String errorMessage;
}
