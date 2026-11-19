package com.testpilot.service.impl;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.CoverageResult;
import com.testpilot.dto.TestQualityResult;
import com.testpilot.dto.TestQualityStaticStats;
import com.testpilot.service.CoverageService;
import com.testpilot.service.ProjectService;
import com.testpilot.service.TestQualityService;
import com.testpilot.utils.ProjectExecutionLock;
import com.testpilot.utils.TestQualityAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 单元测试质量评估服务实现（6.2）。
 * <p>
 * 调用顺序与并发保证：
 * <ol>
 *   <li>外层先获取 {@link ProjectExecutionLock}（失败抛 409）。</li>
 *   <li>锁内调用 {@code projectService.getMavenProjectDir} 定位目录——
 *       必须先加锁再定位，避免目录在定位后被并发替换。</li>
 *   <li>调用 {@code coverageService.runCoverage} 执行测试并生成覆盖率。
 *       其内部会再次获取同一把锁；{@link ProjectExecutionLock} 基于 {@code ReentrantLock}，
 *       同线程重入安全，内层 unlock 仅递减 hold count，不会提前释放外层锁。</li>
 *   <li>调用 {@link TestQualityAnalyzer#analyze} 在锁保护内完成静态分析，
 *       保证静态统计与执行/覆盖率数据来自同一时刻。</li>
 * </ol>
 * 静态分析的整体异常通过 {@code staticStats.analysisError} 表达，绝不丢弃已取得的
 * {@code coverageResult}；执行或覆盖率异常按既有语义原样抛出（finally 仍会释放锁）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestQualityServiceImpl implements TestQualityService {

    private final ProjectService projectService;
    private final CoverageService coverageService;
    private final ProjectExecutionLock projectExecutionLock;

    @Override
    public TestQualityResult evaluate(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(400, "projectId 不能为空");
        }

        // 外层持锁：保证 runCoverage 与 static analyze 在同一临界区完成
        if (!projectExecutionLock.tryLock(projectId)) {
            throw new BusinessException(409, "该项目正在执行其他操作，请稍后重试");
        }
        try {
            // D2：先加锁，再定位项目目录
            Path mavenProjectDir = projectService.getMavenProjectDir(projectId);

            // runCoverage 内部重入同一把 ReentrantLock，安全
            CoverageResult coverageResult = coverageService.runCoverage(projectId);

            // 锁保护内静态分析：异常通过 analysisError 表达，不抛出、不丢弃 coverageResult
            TestQualityStaticStats staticStats = TestQualityAnalyzer.analyze(mavenProjectDir);

            return new TestQualityResult(projectId, staticStats, coverageResult);
        } finally {
            projectExecutionLock.unlock(projectId);
        }
    }
}
