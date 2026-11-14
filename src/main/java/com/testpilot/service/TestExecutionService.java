package com.testpilot.service;

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
}
