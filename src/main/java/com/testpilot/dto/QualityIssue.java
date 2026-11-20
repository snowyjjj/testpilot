package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单条规则化改进建议。
 * <p>
 * 所有字段必须来自实际统计数据，不得编造不存在的类名、方法名或文件路径。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualityIssue implements Serializable {

    /**
     * 问题类型：
     * EMPTY_TEST / NO_ASSERTION / SKIPPED_TEST / PARSE_FAILED /
     * LOW_BRANCH / LOW_LINE / TEST_FAILED / COMPILE_ERROR /
     * LOW_METHOD_DENSITY / LOW_ASSERTION_DENSITY
     */
    private String category;

    /** 问题描述（中文，定量，如"CalculatorTest 有 3 个空测试"） */
    private String description;

    /** 改进建议（中文） */
    private String suggestion;

    /** 数据依据（定量描述，如"平均每类 1.2 个测试方法，目标 5"） */
    private String evidence;

    /** 定位文件路径（如 src/test/java/com/example/CalculatorTest.java）；全局问题可为 null */
    private String filePath;
}
