package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单轮修复记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepairRecord implements Serializable {

    /** 修复轮次（从 1 开始） */
    private int retryNumber;

    /** 失败类型：COMPILE / TEST */
    private String failureType;

    /** 反馈摘要 */
    private String feedbackSummary;

    /** 本轮是否成功生成并保存了修复代码 */
    private boolean repaired;

    /** 修复后的执行结果 */
    private TestExecutionResult executionResult;
}
