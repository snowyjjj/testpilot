package com.testpilot.service;

import com.testpilot.dto.CoverageResult;
import com.testpilot.dto.TestExecutionResult;

public interface TestExecutionService {

    /**
     * 对指定项目执行 Maven test。
     * 同一项目不允许并发执行；不接受用户传入的命令或参数。
     *
     * @param projectId 项目 ID
     * @return 执行结果
     */
    TestExecutionResult executeTests(String projectId);

    /**
     * 对指定项目执行带覆盖率收集的 Maven test。
     * 使用 JaCoCo 命令行插件（prepare-agent test + report 两次调用），不修改用户 pom.xml。
     * 测试执行状态与覆盖率状态独立，覆盖率报告生成失败不篡改原始测试执行结果。
     * 同一项目不允许并发执行（复用 ProjectExecutionLock）。
     *
     * @param projectId 项目 ID
     * @return 覆盖率执行结果
     */
    CoverageResult executeTestsWithCoverage(String projectId);
}
