package com.testpilot.service;

import com.testpilot.dto.CoverageResult;

/**
 * 覆盖率服务接口。委托 TestExecutionService.executeTestsWithCoverage() 完成实际工作。
 */
public interface CoverageService {

    /**
     * 对指定项目执行覆盖率收集与报告生成。
     *
     * @param projectId 项目 ID
     * @return 覆盖率结果（含原始测试执行结果 + 覆盖率状态和指标）
     */
    CoverageResult runCoverage(String projectId);
}
