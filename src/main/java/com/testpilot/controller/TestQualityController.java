package com.testpilot.controller;

import com.testpilot.common.Result;
import com.testpilot.dto.TestQualityResult;
import com.testpilot.service.TestQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单元测试质量评估接口（6.2）。
 * <p>
 * 对指定项目执行一次完整评估：测试执行 + JaCoCo 覆盖率 + 测试代码静态质量统计，
 * 整合为 {@link TestQualityResult} 返回。
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class TestQualityController {

    private final TestQualityService testQualityService;

    /**
     * 评估指定项目的单元测试质量。
     *
     * @param projectId 项目 ID
     * @return 整合后的质量评估结果
     */
    @PostMapping("/{projectId}/quality")
    public Result<TestQualityResult> evaluate(@PathVariable String projectId) {
        return Result.success(testQualityService.evaluate(projectId));
    }
}
