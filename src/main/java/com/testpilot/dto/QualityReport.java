package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试质量评分报告（6.3）。
 * <p>
 * 在 TestQualityResult 的静态统计 + 覆盖率执行结果基础上，
 * 由 {@link com.testpilot.utils.QualityScorer} 纯函数计算得出，
 * 不依赖 Spring 容器、不做文件 I/O、不做数据库访问。
 * <p>
 * 三态语义：
 * <ul>
 *   <li>{@code overallScore=null, grade="N/A"}：可评维度 < 2，或无运行时维度可评，
 *       无法给出有意义的综合等级；各单维度得分仍完整返回。</li>
 *   <li>{@code overallScore∈[0,40), grade="F"}：测试质量很差。</li>
 *   <li>{@code overallScore∈[40,100], grade="D/C/B/A"}：正式等级，边界见 QualityScorer 的
 *       GRADE_A_MIN / GRADE_B_MIN / GRADE_C_MIN / GRADE_D_MIN 常量。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualityReport implements Serializable {

    /** 综合分（未舍入的精确值）；null 表示综合分不可评 */
    private Double overallScore;

    /** 质量等级：A/B/C/D/F/N/A */
    private String grade;

    /** 四个维度的详细结果，全部返回；不可评的维度 available=false 且 score=null */
    private List<QualityDimension> dimensions = new ArrayList<>();

    /** 规则化改进建议列表；无建议时为空列表，不为 null */
    private List<QualityIssue> issues = new ArrayList<>();

    /**
     * 可信度警告，说明静态统计可能不完整的原因：
     * <ul>
     *   <li>整体静态分析失败（analysisError ≠ null）</li>
     *   <li>部分文件解析失败（parseFailedFileCount > 0 且 analysisError = null）</li>
     * </ul>
     * 无警告时为 null。
     */
    private String reliabilityWarning;
}
