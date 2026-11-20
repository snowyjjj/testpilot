package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单个评分维度的详细结果。
 * <p>
 * 三态语义：
 * <ul>
 *   <li>{@code available=false}：该维度数据不可用，{@code score} 必须为 null。
 *       区别于"最差得分 0"，0 分代表有数据且数据是真的差。</li>
 *   <li>{@code available=true, score=0}：有数据，且得分是真的 0（如覆盖率真 0%）。</li>
 *   <li>{@code available=true, score∈(0,100]}：正常得分。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualityDimension implements Serializable {

    // ===== 维度类型常量（Java 字符串，延续项目风格，不引入 Java enum）=====

    /** 有效性：测试执行后通过/失败的结果。运行时维度 */
    public static final String EFFECTIVENESS = "EFFECTIVENESS";
    /** 覆盖率：JaCoCo 报告的代码覆盖程度。运行时维度 */
    public static final String COVERAGE = "COVERAGE";
    /** 充分性：测试代码内部的数量密度。静态维度 */
    public static final String SUFFICIENCY = "SUFFICIENCY";
    /** 规范性：测试代码是否存在明显质量缺陷。静态维度 */
    public static final String CORRECTNESS = "CORRECTNESS";

    // ===== 字段 =====

    /** 维度类型：EFFECTIVENESS / COVERAGE / SUFFICIENCY / CORRECTNESS */
    private String type;

    /** 中文显示名，如"有效性"、"覆盖率" */
    private String displayName;

    /** 是否有数据可评；false 时 score 必须为 null */
    private boolean available;

    /** 百分制得分；不可评时为 null，真 0 分是 Double(0.0) 不是 null */
    private Double score;

    /** 不可评原因说明，或可评时的补充说明；可评且无补充时为 null */
    private String reason;

    /** 该维度在综合分中的权重（0.30/0.25/0.20/0.25） */
    private double weight;
}
