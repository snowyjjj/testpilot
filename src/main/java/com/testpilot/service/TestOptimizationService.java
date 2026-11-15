package com.testpilot.service;

import com.testpilot.dto.TestOptimizationRequest;
import com.testpilot.dto.TestOptimizationResult;

public interface TestOptimizationService {

    /**
     * 对指定项目的测试执行自动优化闭环：
     * 执行测试 → 失败则收集反馈 → 调用 LLM 修复 → 重新执行，最多 maxRetries 轮。
     *
     * @param projectId 项目 ID
     * @param request   优化请求（类名、方法名、参数类型、最大修复次数）
     * @return 优化结果
     */
    TestOptimizationResult optimize(String projectId, TestOptimizationRequest request);
}
