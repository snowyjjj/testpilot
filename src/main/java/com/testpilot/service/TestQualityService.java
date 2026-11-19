package com.testpilot.service;

import com.testpilot.dto.TestQualityResult;

/**
 * 单元测试质量评估服务接口（6.2）。
 * <p>
 * 将测试执行结果、JaCoCo 覆盖率数据与测试代码静态质量统计整合为一次评估结果。
 */
public interface TestQualityService {

    /**
     * 评估指定项目的单元测试质量。
     * <p>
     * 流程：获取项目锁 → 定位 Maven 目录 → 执行覆盖率（内层重入同一锁）→
     * 静态分析测试源码 → 组合返回。全程在同一项目锁保护内完成。
     *
     * @param projectId 项目 ID
     * @return 整合后的质量评估结果
     */
    TestQualityResult evaluate(String projectId);
}
