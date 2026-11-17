package com.testpilot.controller;

import com.testpilot.common.Result;
import com.testpilot.dto.CoverageResult;
import com.testpilot.service.CoverageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 覆盖率分析接口。
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class CoverageController {

    private final CoverageService coverageService;

    /**
     * 对指定项目执行覆盖率收集与报告生成。
     * <p>
     * 内部通过 JaCoCo 命令行插件（不修改用户 pom.xml）执行两次 Maven 调用：
     * {@code prepare-agent test} 收集覆盖率数据 → {@code report} 生成 XML 报告。
     * 测试执行状态与覆盖率状态独立返回。
     *
     * @param projectId 项目 ID
     * @return 覆盖率执行结果
     */
    @PostMapping("/{projectId}/coverage")
    public Result<CoverageResult> runCoverage(@PathVariable String projectId) {
        return Result.success(coverageService.runCoverage(projectId));
    }
}
