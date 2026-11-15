package com.testpilot.controller;

import com.testpilot.common.Result;
import com.testpilot.dto.TestOptimizationRequest;
import com.testpilot.dto.TestOptimizationResult;
import com.testpilot.service.TestOptimizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试优化接口。
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class TestOptimizationController {

    private final TestOptimizationService testOptimizationService;

    /**
     * 对已生成的测试进行自动优化（执行 → 反馈 → LLM 修复 → 重新执行）。
     */
    @PostMapping("/{projectId}/tests/optimize")
    public Result<TestOptimizationResult> optimizeTests(
            @PathVariable String projectId,
            @Valid @RequestBody TestOptimizationRequest request) {
        TestOptimizationResult result = testOptimizationService.optimize(projectId, request);
        return Result.success(result);
    }
}
