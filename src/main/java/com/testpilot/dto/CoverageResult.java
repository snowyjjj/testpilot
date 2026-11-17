package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 覆盖率执行结果。包含原始测试执行结果和独立的覆盖率状态。
 * <p>
 * 测试执行状态和覆盖率状态严格独立：
 * <ul>
 *   <li>{@code testExecutionResult} 原样保留 Maven test 执行结果，永不被覆盖率步骤篡改。</li>
 *   <li>{@code coverageStatus} 描述覆盖率报告的生成状态：
 *       <ul>
 *         <li>{@code COVERAGE_GENERATED}：jacoco.xml 正常解析且至少一项 metric available=true</li>
 *         <li>{@code NO_COVERAGE}：测试状态为 COMPILE_ERROR / MAVEN_ERROR / TIMEOUT / NO_REPORTS，
 *             无有效测试执行，无覆盖率可生成</li>
 *         <li>{@code NO_REPORT}：测试已执行（PASSED 或 FAILED）但 jacoco.exec 未生成、
 *             jacoco.xml 不存在、损坏或无有效 counter</li>
 *       </ul>
 *   </li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoverageResult implements Serializable {

    private String projectId;

    /** 原始测试执行结果，与覆盖率状态完全独立 */
    private TestExecutionResult testExecutionResult;

    /**
     * 覆盖率状态：COVERAGE_GENERATED / NO_COVERAGE / NO_REPORT
     */
    private String coverageStatus;

    /** 各项覆盖率指标；NO_COVERAGE 时为空列表 */
    private List<CoverageMetric> metrics;

    /** 覆盖率相关错误信息（如 jacoco.exec 未生成原因、XML 解析异常等），独立于 testExecutionResult.errorMessage */
    private String coverageError;
}
