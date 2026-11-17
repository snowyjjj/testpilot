package com.testpilot.service.impl;

import com.testpilot.dto.CoverageResult;
import com.testpilot.service.CoverageService;
import com.testpilot.service.TestExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 覆盖率服务实现。薄委托层，实际工作由 {@link TestExecutionService#executeTestsWithCoverage} 完成。
 * <p>
 * 覆盖范围：cleanSurefire → cleanJacoco → prepare-agent test → 解析 surefire →
 * （若 PASSED/FAILED）检查 exec → jacoco:report → 解析 jacoco.xml → 返回结果。
 * 全部在同一项目锁内完成（与普通测试执行、测试优化流程互斥）。
 */
@Service
@RequiredArgsConstructor
public class CoverageServiceImpl implements CoverageService {

    private final TestExecutionService testExecutionService;

    @Override
    public CoverageResult runCoverage(String projectId) {
        return testExecutionService.executeTestsWithCoverage(projectId);
    }
}
