package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 测试优化结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestOptimizationResult implements Serializable {

    private String projectId;

    /** 初始执行结果（修复前） */
    private TestExecutionResult initialExecutionResult;

    /** 实际执行的修复轮次 */
    private int retryCount;

    /** 优化是否成功（最终测试通过） */
    private boolean optimizationSuccess;

    /** 最终执行结果 */
    private TestExecutionResult finalExecutionResult;

    /** 每轮修复记录 */
    private List<RepairRecord> repairRecords;
}
