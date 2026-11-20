package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单元测试质量评估整合结果。
 * 6.3 新增 qualityReport 字段，向后兼容：原有 3 参构造器保留（qualityReport 默认 null）。
 * <p>
 * 将一次评估中的两部分数据组合返回：
 * <ul>
 *   <li>{@code staticStats}：基于 JavaParser AST 的测试代码静态质量统计，
 *       由 {@link com.testpilot.utils.TestQualityAnalyzer#analyze} 在项目锁保护内生成。</li>
 *   <li>{@code coverageResult}：同一次评估中执行得到的测试执行结果与 JaCoCo 覆盖率数据，
 *       内部已包含 {@link TestExecutionResult}，此处不重复保存。</li>
 * </ul>
 * 两部分数据在同一项目锁保护期间依次采集，保证来自同一时刻的项目状态。
 * 任一环节出现异常都不会丢弃另一部分已取得的数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestQualityResult implements Serializable {

    /** 项目 ID */
    private String projectId;

    /** 测试代码静态质量统计；analysisError 非 null 表示整体分析异常但已保留部分数据 */
    private TestQualityStaticStats staticStats;

    /** 覆盖率执行结果（含原始测试执行结果） */
    private CoverageResult coverageResult;

    /**
     * 测试质量评分报告（6.3）。
     * 由 {@link com.testpilot.utils.QualityScorer} 基于 staticStats + coverageResult 纯函数计算得出。
     * null 表示 6.3 评分未执行（向后兼容，老版本客户端忽略此字段）。
     */
    private QualityReport qualityReport;

    /** 6.2 向后兼容的 3 参构造器：qualityReport 默认 null。 */
    public TestQualityResult(String projectId, TestQualityStaticStats staticStats, CoverageResult coverageResult) {
        this.projectId = projectId;
        this.staticStats = staticStats;
        this.coverageResult = coverageResult;
        this.qualityReport = null;
    }
}
