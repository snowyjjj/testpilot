package com.testpilot.controller;

import com.testpilot.common.Result;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.service.TestExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class TestExecutionController {

    private final TestExecutionService testExecutionService;

    /**
     * 执行指定项目的单元测试（Maven test）。
     * 请求体为空，不接受用户传入的命令或参数。
     */
    @PostMapping("/{projectId}/tests/execute")
    public Result<TestExecutionResult> executeTests(@PathVariable String projectId) {
        TestExecutionResult result = testExecutionService.executeTests(projectId);
        return Result.success(result);
    }
}
