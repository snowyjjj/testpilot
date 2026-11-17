package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 覆盖率指标：某一项 counter 的统计结果。
 * <p>
 * 三态语义：
 * <ul>
 *   <li>{@code available=false}：JaCoCo XML 中未报告该 counter 类型（如项目无 BRANCH）</li>
 *   <li>{@code available=true, percentage=null}：有该 counter 但 missed+covered=0（该维度无元素可覆盖，N/A）</li>
 *   <li>{@code available=true, percentage=0.0}：missed>0 covered=0，真正的 0% 覆盖率</li>
 *   <li>{@code available=true, percentage=C/(M+C)*100}：实际覆盖率</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoverageMetric implements Serializable {

    /** counter 类型：INSTRUCTION / BRANCH / LINE / METHOD / CLASS */
    private String type;

    /** JaCoCo 是否报告了该 counter 类型 */
    private boolean available;

    /** missed 数量 */
    private int missed;

    /** covered 数量 */
    private int covered;

    /** 覆盖率百分比；null 表示无统计意义（available=false 或 missed+covered=0） */
    private Double percentage;
}
