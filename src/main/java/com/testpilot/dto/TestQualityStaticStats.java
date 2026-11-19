package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目测试代码的静态质量统计结果。
 * <p>
 * 由 {@link com.testpilot.utils.TestQualityAnalyzer#analyze} 遍历
 * Maven 项目根目录下 {@code src/test/java} 中的所有 Java 文件聚合得到。
 * <p>
 * 所有计数均基于源码静态分析（JavaParser AST），不依赖测试执行结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestQualityStaticStats implements Serializable {

    /** 包含至少一个 @Test 方法的测试类数量 */
    private int testClassCount;

    /** 全项目 @Test 注解方法总数（兼容 JUnit4 / JUnit5） */
    private int totalTestMethods;

    /** 全项目识别到的断言调用总数 */
    private int totalAssertions;

    /** 方法体没有实际语句的空测试方法总数 */
    private int emptyTestMethods;

    /** 方法体中没有识别到断言调用的测试方法总数（空测试也计入此项） */
    private int noAssertionTestMethods;

    /** 被 @Ignore（JUnit4）或 @Disabled（JUnit5）标注的测试方法总数 */
    private int skippedTestMethods;

    /** JavaParser 解析失败的文件数量 */
    private int parseFailedFileCount;

    /** 解析失败文件的相对路径列表 */
    private List<String> parseFailedFiles = new ArrayList<>();

    /** 每个测试类的统计明细 */
    private List<TestClassStat> classStats = new ArrayList<>();
}
