package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单个测试类的静态质量统计明细。
 * <p>
 * 由 {@link com.testpilot.utils.TestQualityAnalyzer} 逐文件解析生成，
 * 用于质量评分与改进建议阶段定位问题所在的测试类。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestClassStat implements Serializable {

    /** 相对项目 Maven 根目录的测试文件路径，如 src/test/java/com/example/CalculatorTest.java */
    private String filePath;

    /** 测试类简单名称，如 CalculatorTest */
    private String className;

    /** 该类中 @Test 注解方法总数（兼容 JUnit4 / JUnit5） */
    private int testMethodCount;

    /** 该类中识别到的断言调用总数 */
    private int assertionCount;

    /** 方法体没有实际语句的空测试方法数量 */
    private int emptyTestMethodCount;

    /** 方法体中没有识别到断言调用的测试方法数量（空测试也计入此项） */
    private int noAssertionTestMethodCount;

    /** 被 @Ignore（JUnit4）或 @Disabled（JUnit5）标注的测试方法数量 */
    private int skippedTestMethodCount;
}
