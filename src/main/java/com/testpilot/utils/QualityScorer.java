package com.testpilot.utils;

import com.testpilot.dto.CoverageMetric;
import com.testpilot.dto.CoverageResult;
import com.testpilot.dto.QualityDimension;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.dto.TestQualityResult;
import com.testpilot.dto.TestQualityStaticStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试质量评分计算器（6.3）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>纯函数、无副作用</b>：所有方法都是 {@code public static}，输入 DTO，输出 DTO。
 *       不依赖 Spring 容器、不做文件 I/O、不访问数据库。这使得单元测试可以直接构造
 *       DTO 传参，不需要容器，测试速度极快且行为可预测。</li>
 *   <li><b>集中常量管理</b>：四维度权重、充分性目标密度、覆盖率指标权重、等级边界全部
 *       在此类顶部声明，便于后续调优。</li>
 *   <li><b>严格区分不可评与真 0 分</b>：维度 {@code available=false} 代表"数据不存在，
 *       不是质量差"，score 必须为 null；可用但真的差是 {@code score=0.0}。</li>
 *   <li><b>防御性校验</b>：所有计数 >= 0、计数和一致、状态与计数匹配、analysisError 非空
 *       时静态维度不可评等情况均有处理，避免异常数据导致 NaN / 负分 / 无意义综合等级。</li>
 * </ul>
 */
public final class QualityScorer {

    // ============ 四维度权重（综合分时加权归一化） ============

    /** 有效性：测试执行后通过/失败结果（运行时维度） */
    public static final double W_EFFECTIVENESS = 0.30;
    /** 覆盖率：JaCoCo 报告（运行时维度） */
    public static final double W_COVERAGE = 0.25;
    /** 充分性：测试代码数量密度（静态维度） */
    public static final double W_SUFFICIENCY = 0.20;
    /** 规范性：空测试/无断言/跳过等质量缺陷（静态维度） */
    public static final double W_CORRECTNESS = 0.25;

    /** 运行时维度 type 集合——综合分条件判断用 */
    public static final Set<String> RUNTIME_TYPES = Set.of(
            QualityDimension.EFFECTIVENESS, QualityDimension.COVERAGE);

    // ============ 充分性目标密度（单向封顶评分的中心点） ============

    /** 每个测试类平均方法数目标（单向封顶公式：达到即满分，超过不扣分） */
    public static final double TARGET_METHODS_PER_CLASS = 5.0;
    /** 每个测试方法平均断言数目标（单向封顶公式） */
    public static final double TARGET_ASSERTIONS_PER_TEST = 2.5;

    // ============ 等级边界（左闭右开，按未舍入综合分判断） ============

    /** 质量等级边界：A/B/C/D/F/N/A，key 是等级名，value 是下限（含） */
    public static final double GRADE_A_MIN = 90.0;
    public static final double GRADE_B_MIN = 75.0;
    public static final double GRADE_C_MIN = 60.0;
    public static final double GRADE_D_MIN = 40.0;
    public static final double GRADE_F_MIN = 0.0;

    /** 综合分不可评时的等级标记 */
    public static final String GRADE_NA = "N/A";

    // ============ 状态常量（避免到处散落硬编码字符串） ============

    private static final String STATUS_PASSED = "PASSED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String COV_GENERATED = "COVERAGE_GENERATED";
    private static final String COV_NO_COVERAGE = "NO_COVERAGE";
    private static final String COV_NO_REPORT = "NO_REPORT";

    // ============ 私有构造器（静态工具类禁止实例化） ============

    private QualityScorer() {
    }

    // ============ 入口：一次性组装完整 QualityReport ============

    /**
     * 基于已有 TestQualityResult 组装完整的 QualityReport。
     * <p>
     * 这是 6.3 的主入口，供 Service 层在锁外调用（纯计算，无 I/O）。
     *
     * @param input 6.2 已有的 TestQualityResult（staticStats + coverageResult），不可为 null
     * @return 完整的 QualityReport；不会返回 null，但 overallScore / grade / 各维度 score
     *         可能为 null 表示不可评
     */
    public static com.testpilot.dto.QualityReport score(TestQualityResult input) {
        if (input == null) {
            return buildUnavailable("TestQualityResult 为 null，无法评分");
        }

        TestQualityStaticStats stats = input.getStaticStats();
        CoverageResult cov = input.getCoverageResult();
        TestExecutionResult exec = cov != null ? cov.getTestExecutionResult() : null;

        List<QualityDimension> dims = new ArrayList<>(4);
        dims.add(scoreEffectiveness(exec));
        dims.add(scoreCoverage(cov));
        dims.add(scoreSufficiency(stats));
        dims.add(scoreCorrectness(stats));

        // 综合分计算与等级判定
        int availableCount = 0;
        boolean hasRuntime = false;
        double weightedSum = 0.0;
        double weightSum = 0.0;
        for (QualityDimension d : dims) {
            if (d.isAvailable() && d.getScore() != null) {
                availableCount++;
                double w = d.getWeight();
                weightedSum += d.getScore() * w;
                weightSum += w;
                if (RUNTIME_TYPES.contains(d.getType())) {
                    hasRuntime = true;
                }
            }
        }

        Double overallScore = null;
        String grade = GRADE_NA;
        if (availableCount >= 2 && hasRuntime && weightSum > 0) {
            overallScore = weightedSum / weightSum;
            grade = grade(overallScore, availableCount, hasRuntime);
        } else {
            StringBuilder reason = new StringBuilder();
            if (availableCount < 2) {
                reason.append("可评维度不足 2 个");
            }
            if (!hasRuntime) {
                if (reason.length() > 0) reason.append("，");
                reason.append("无运行时维度（有效性或覆盖率）可评");
            }
            if (availableCount < 2 || !hasRuntime) {
                reason.insert(0, "综合分不可评：");
                overallScore = null;
                grade = GRADE_NA;
            }
        }

        com.testpilot.dto.QualityReport report = new com.testpilot.dto.QualityReport();
        report.setOverallScore(overallScore);
        report.setGrade(grade);
        report.setDimensions(dims);

        // reliabilityWarning（整体/部分静态分析失败）
        report.setReliabilityWarning(buildReliabilityWarning(stats));

        // 改进建议（第二批实现：基于实际统计数据规则化生成）
        report.setIssues(buildIssues(input, dims));

        return report;
    }

    private static com.testpilot.dto.QualityReport buildUnavailable(String reason) {
        com.testpilot.dto.QualityReport report = new com.testpilot.dto.QualityReport();
        report.setOverallScore(null);
        report.setGrade(GRADE_NA);
        report.setDimensions(new ArrayList<>());
        report.setIssues(new ArrayList<>());
        report.setReliabilityWarning(reason);
        return report;
    }

    // ============ 维度一：有效性 Effectiveness ============

    /**
     * 有效性维度评分。分母为 passedTests + failedTests（跳过测试排除）。
     * <p>
     * 严格校验计数一致性：
     * <ul>
     *   <li>所有计数 >= 0</li>
     *   <li>passed + failed + skipped == total</li>
     *   <li>passed + failed > 0</li>
     *   <li>PASSED 状态 failed 必须为 0</li>
     *   <li>FAILED 状态 failed 必须 > 0</li>
     * </ul>
     * 任意一条不满足 → available=false, score=null，并给出数据异常原因。
     */
    public static QualityDimension scoreEffectiveness(TestExecutionResult exec) {
        String type = QualityDimension.EFFECTIVENESS;
        String display = "有效性";
        double weight = W_EFFECTIVENESS;

        if (exec == null) {
            return unavailableDim(type, display, weight, "无测试执行结果");
        }

        String status = exec.getStatus();
        if (status == null) {
            return unavailableDim(type, display, weight, "测试执行状态为 null");
        }

        // 只有 PASSED / FAILED 能计算有效性
        boolean executable = STATUS_PASSED.equals(status) || STATUS_FAILED.equals(status);
        if (!executable) {
            return unavailableDim(type, display, weight,
                    "测试未成功执行（状态=" + status + "），有效性不可评");
        }

        // 计数防御：全部 >= 0
        int total = exec.getTotalTests();
        int passed = exec.getPassedTests();
        int failed = exec.getFailedTests();
        int skipped = exec.getSkippedTests();
        if (total < 0 || passed < 0 || failed < 0 || skipped < 0) {
            return unavailableDim(type, display, weight,
                    "测试计数存在负值：total=" + total + ", passed=" + passed
                            + ", failed=" + failed + ", skipped=" + skipped);
        }

        // 计数一致性：passed + failed + skipped == total
        if (passed + failed + skipped != total) {
            return unavailableDim(type, display, weight,
                    "测试计数不一致：passed(" + passed + ")+failed(" + failed
                            + ")+skipped(" + skipped + ") != total(" + total + ")");
        }

        // 状态约束
        if (STATUS_PASSED.equals(status) && failed > 0) {
            return unavailableDim(type, display, weight,
                    "状态 PASSED 但 failedTests=" + failed + " > 0，数据异常");
        }
        if (STATUS_FAILED.equals(status) && failed <= 0) {
            return unavailableDim(type, display, weight,
                    "状态 FAILED 但 failedTests=" + failed + " <= 0，数据异常");
        }

        // 分母：排除 skipped
        int denominator = passed + failed;
        if (denominator <= 0) {
            return unavailableDim(type, display, weight,
                    "passedTests + failedTests = " + denominator + "，无实际执行的测试");
        }

        double score = clamp(100.0 * passed / denominator, 0.0, 100.0);
        String reason = "基于 passed(" + passed + ") / (passed + failed)(" + denominator
                + ") 计算，跳过测试(" + skipped + ") 排除在外";
        return new QualityDimension(type, display, true, score, reason, weight);
    }

    // ============ 维度二：覆盖率 Coverage ============

    /**
     * 覆盖率维度评分。仅统计 COVERAGE_GENERATED 状态下的 LINE / BRANCH / METHOD 三种指标。
     * available=false 或 percentage=null 的指标自动跳过，权重归一化。
     * 全部跳过 → 不可评。真 0% 覆盖率保留为 score=0.0（available=true）。
     */
    public static QualityDimension scoreCoverage(CoverageResult cov) {
        String type = QualityDimension.COVERAGE;
        String display = "覆盖率";
        double weight = W_COVERAGE;

        if (cov == null) {
            return unavailableDim(type, display, weight, "无覆盖率结果");
        }
        if (!COV_GENERATED.equals(cov.getCoverageStatus())) {
            return unavailableDim(type, display, weight,
                    "覆盖率未生成（状态=" + cov.getCoverageStatus() + "）");
        }
        List<CoverageMetric> metrics = cov.getMetrics();
        if (metrics == null || metrics.isEmpty()) {
            return unavailableDim(type, display, weight, "覆盖率指标列表为空");
        }

        // 覆盖率权重配置
        Map<String, Double> covWeights = new LinkedHashMap<>();
        covWeights.put("LINE", 0.50);
        covWeights.put("BRANCH", 0.30);
        covWeights.put("METHOD", 0.20);

        double weightedSum = 0.0;
        double weightSum = 0.0;
        List<String> used = new ArrayList<>();
        for (Map.Entry<String, Double> entry : covWeights.entrySet()) {
            CoverageMetric m = findMetric(metrics, entry.getKey());
            if (m != null && m.isAvailable() && m.getPercentage() != null) {
                double v = clamp(m.getPercentage(), 0.0, 100.0);
                weightedSum += v * entry.getValue();
                weightSum += entry.getValue();
                used.add(entry.getKey());
            }
        }

        if (weightSum <= 0) {
            return unavailableDim(type, display, weight,
                    "LINE / BRANCH / METHOD 指标均不可用");
        }

        double score = weightedSum / weightSum;
        String reason = "基于 " + String.join("+", used)
                + " 计算（权重归一化）";
        return new QualityDimension(type, display, true, score, reason, weight);
    }

    // ============ 维度三：充分性 Sufficiency ============

    /**
     * 充分性：测试代码内部的数量密度评估。
     * <p>
     * 前提：testClassCount > 0 且 analysisError == null（整体静态分析失败时不可评）。
     * <p>
     * 单向封顶公式：
     * <ul>
     *   <li>方法密度分 = clamp(avgMethodsPerClass / 5.0 * 100, 0, 100)</li>
     *   <li>断言密度分 = clamp(avgAssertionsPerTest / 2.5 * 100, 0, 100)</li>
     *   <li>充分性 = 两者加权平均（各 50%）</li>
     * </ul>
     * 高密度时不扣分、也不额外加分——这是测试代码内部的启发式密度评价，
     * 不能代表对业务代码测试充分性的绝对判断。
     */
    public static QualityDimension scoreSufficiency(TestQualityStaticStats stats) {
        String type = QualityDimension.SUFFICIENCY;
        String display = "充分性";
        double weight = W_SUFFICIENCY;

        if (stats == null) {
            return unavailableDim(type, display, weight, "无静态统计数据");
        }
        if (stats.getAnalysisError() != null) {
            return unavailableDim(type, display, weight,
                    "整体静态分析失败（analysisError=" + stats.getAnalysisError() + "），无法计算充分性");
        }
        int testClassCount = stats.getTestClassCount();
        int totalMethods = stats.getTotalTestMethods();
        int totalAssertions = stats.getTotalAssertions();

        // 异常值防御：全部计数 >= 0
        if (testClassCount < 0 || totalMethods < 0 || totalAssertions < 0) {
            return unavailableDim(type, display, weight,
                    "静态统计存在负值：testClassCount=" + testClassCount
                            + ", totalTestMethods=" + totalMethods
                            + ", totalAssertions=" + totalAssertions);
        }
        if (testClassCount == 0) {
            return unavailableDim(type, display, weight, "无测试代码（testClassCount=0）");
        }

        double avgMethodsPerClass = (double) totalMethods / testClassCount;
        double avgAssertionsPerTest = totalMethods > 0
                ? (double) totalAssertions / totalMethods
                : 0.0;

        double methodDensityScore = clamp(avgMethodsPerClass / TARGET_METHODS_PER_CLASS * 100.0, 0.0, 100.0);
        double assertionDensityScore = clamp(avgAssertionsPerTest / TARGET_ASSERTIONS_PER_TEST * 100.0, 0.0, 100.0);
        double score = methodDensityScore * 0.5 + assertionDensityScore * 0.5;

        String reason = "方法密度 " + String.format("%.2f", avgMethodsPerClass)
                + "（目标 " + TARGET_METHODS_PER_CLASS + "），"
                + "断言密度 " + String.format("%.2f", avgAssertionsPerTest)
                + "（目标 " + TARGET_ASSERTIONS_PER_TEST + "）";
        return new QualityDimension(type, display, true, score, reason, weight);
    }

    // ============ 维度四：规范性 Correctness ============

    /**
     * 规范性：空测试、无断言、跳过等质量缺陷的扣分评估。
     * <p>
     * 前提：totalTestMethods > 0 且 analysisError == null。
     * <p>
     * 从 100 起扣，各项独立有上限：
     * <ul>
     *   <li>空测试：emptyRate * 40，上限 40</li>
     *   <li>有语句无断言：(noAssertion - empty)/total * 30，上限 30（去重，避免双重扣分）</li>
     *   <li>跳过测试：skippedRate * 20，上限 20</li>
     * </ul>
     * parseFailedFileCount 不参与规范性扣分——6.1 无总文件数字段，
     * 用 testClassCount 近似分母不准确。parseFailed 改为 reliabilityWarning 和改进建议。
     */
    public static QualityDimension scoreCorrectness(TestQualityStaticStats stats) {
        String type = QualityDimension.CORRECTNESS;
        String display = "规范性";
        double weight = W_CORRECTNESS;

        if (stats == null) {
            return unavailableDim(type, display, weight, "无静态统计数据");
        }
        if (stats.getAnalysisError() != null) {
            return unavailableDim(type, display, weight,
                    "整体静态分析失败（analysisError=" + stats.getAnalysisError() + "），无法计算规范性");
        }

        int total = stats.getTotalTestMethods();
        int empty = stats.getEmptyTestMethods();
        int noAssertion = stats.getNoAssertionTestMethods();
        int skipped = stats.getSkippedTestMethods();

        // 异常值防御
        if (total < 0 || empty < 0 || noAssertion < 0 || skipped < 0) {
            return unavailableDim(type, display, weight,
                    "静态统计存在负值：total=" + total + ", empty=" + empty
                            + ", noAssertion=" + noAssertion + ", skipped=" + skipped);
        }
        if (total == 0) {
            return unavailableDim(type, display, weight, "无测试方法可评估（totalTestMethods=0）");
        }
        if (empty > total || noAssertion > total || skipped > total) {
            return unavailableDim(type, display, weight,
                    "静态统计越界：empty=" + empty + ">total=" + total
                            + " / noAssertion=" + noAssertion
                            + " / skipped=" + skipped);
        }
        if (empty > noAssertion) {
            // 理论上 noAssertion ⊇ empty（空测试必然无断言），
            // 如果 empty > noAssertion 说明统计数据不一致。
            return unavailableDim(type, display, weight,
                    "静态统计不一致：emptyTestMethods(" + empty
                            + ") > noAssertionTestMethods(" + noAssertion + ")");
        }

        // 去重：noAssertion ⊇ empty（空测试也无断言），单独评价"有语句但无断言"的部分
        int nonAssertionOnly = Math.max(0, noAssertion - empty);

        double emptyRate = (double) empty / total;
        double nonAssertRate = (double) nonAssertionOnly / total;
        double skippedRate = (double) skipped / total;

        double emptyPenalty = clamp(emptyRate * 40.0, 0.0, 40.0);
        double nonAssertPenalty = clamp(nonAssertRate * 30.0, 0.0, 30.0);
        double skippedPenalty = clamp(skippedRate * 20.0, 0.0, 20.0);

        double score = clamp(100.0 - emptyPenalty - nonAssertPenalty - skippedPenalty, 0.0, 100.0);

        String reason = "空测试=" + emptyRate + "×40=" + String.format("%.1f", emptyPenalty)
                + "，有语句无断言=" + nonAssertRate + "×30=" + String.format("%.1f", nonAssertPenalty)
                + "，跳过=" + skippedRate + "×20=" + String.format("%.1f", skippedPenalty);
        return new QualityDimension(type, display, true, score, reason, weight);
    }

    // ============ 综合分与等级（public static 便于单测） ============

    /**
     * 根据已计算的维度列表得到综合分（未舍入）。
     * 过滤 available=true && score!=null 的维度，按各自权重加权求和再除以权重和（自动归一化）。
     * 无可用维度或权重和为 0 时返回 null。
     */
    public static Double overallScore(List<QualityDimension> dims) {
        if (dims == null || dims.isEmpty()) return null;
        double weightedSum = 0.0;
        double weightSum = 0.0;
        for (QualityDimension d : dims) {
            if (d != null && d.isAvailable() && d.getScore() != null) {
                weightedSum += d.getScore() * d.getWeight();
                weightSum += d.getWeight();
            }
        }
        if (weightSum <= 0) return null;
        return weightedSum / weightSum;
    }

    /**
     * 根据未舍入的综合分得到等级字符串。
     * 边界：A[90,100] / B[75,90) / C[60,75) / D[40,60) / F[0,40)
     *
     * @param overallScore 综合分（未舍入），null 时返回 "N/A"
     * @return A / B / C / D / F / N/A
     */
    public static String grade(Double overallScore, int availableCount, boolean hasRuntime) {
        if (overallScore == null || availableCount < 2 || !hasRuntime) {
            return GRADE_NA;
        }
        if (overallScore >= GRADE_A_MIN) return "A";
        if (overallScore >= GRADE_B_MIN) return "B";
        if (overallScore >= GRADE_C_MIN) return "C";
        if (overallScore >= GRADE_D_MIN) return "D";
        return "F";
    }

    // ============ reliabilityWarning 生成 ============

    /**
     * 根据静态统计的 analysisError 和 parseFailedFileCount 生成可信度警告。
     * <p>
     * 触发条件：
     * <ul>
     *   <li>analysisError != null → 整体静态分析失败，优先显示</li>
     *   <li>parseFailedFileCount > 0 && analysisError == null → 个别文件解析失败</li>
     * </ul>
     * 均无 → 返回 null。
     */
    public static String buildReliabilityWarning(TestQualityStaticStats stats) {
        if (stats == null) return null;
        if (stats.getAnalysisError() != null) {
            return "整体静态分析失败（" + stats.getAnalysisError()
                    + "），静态质量评分基于不完整数据，仅供参考";
        }
        int parseFailed = stats.getParseFailedFileCount();
        if (parseFailed > 0) {
            return "有 " + parseFailed + " 个测试文件解析失败（语法错误），部分静态统计可能不完整";
        }
        return null;
    }

    // ============ 改进建议生成（第二批） ============

    /**
     * 基于 TestQualityResult 和已计算的维度列表，规则化生成改进建议。
     * <p>
     * 10 类规则（按触发顺序）：
     * <ul>
     *   <li>EMPTY_TEST / NO_ASSERTION / SKIPPED_TEST / LOW_METHOD_DENSITY / LOW_ASSERTION_DENSITY
     *       —— 依赖静态统计，analysisError != null 时不生成</li>
     *   <li>PARSE_FAILED —— 单文件解析失败（analysisError == null 且 parseFailedFileCount > 0）</li>
     *   <li>LOW_BRANCH / LOW_LINE —— 依赖覆盖率 COVERAGE_GENERATED</li>
     *   <li>TEST_FAILED / COMPILE_ERROR —— 依赖执行状态</li>
     * </ul>
     * 所有 issue 的 evidence 来自真实统计字段，类级定位只使用 TestClassStat 实际数据。
     */
    public static List<com.testpilot.dto.QualityIssue> buildIssues(
            TestQualityResult input, List<QualityDimension> dims) {
        List<com.testpilot.dto.QualityIssue> issues = new ArrayList<>();
        if (input == null) return issues;

        TestQualityStaticStats stats = input.getStaticStats();
        CoverageResult cov = input.getCoverageResult();
        TestExecutionResult exec = cov != null ? cov.getTestExecutionResult() : null;

        // ====== 静态相关 issues：analysisError != null 时整体跳过 ======
        boolean staticUsable = stats != null && stats.getAnalysisError() == null;

        if (staticUsable) {
            buildStaticIssues(stats, issues);
            buildDensityIssues(stats, dims, issues);
        }

        // PARSE_FAILED 独立于 staticUsable：整体分析失败时不生成（因为 parseFailedFileCount 可能不可靠），
        // 只有 analysisError==null 且 parseFailedFileCount>0 才说明"部分文件解析失败"
        if (staticUsable && stats.getParseFailedFileCount() > 0) {
            issues.add(new com.testpilot.dto.QualityIssue(
                    "PARSE_FAILED",
                    stats.getParseFailedFileCount() + " 个测试文件语法错误无法解析",
                    "请检查这些文件的语法正确性，修复后静态统计将更完整",
                    "失败文件数=" + stats.getParseFailedFileCount(),
                    null
            ));
        }

        // ====== 覆盖率 issues ======
        buildCoverageIssues(cov, issues);

        // ====== 执行状态 issues ======
        buildExecutionIssues(exec, issues);

        return issues;
    }

    /**
     * 静态质量类 issues：EMPTY_TEST / NO_ASSERTION / SKIPPED_TEST。
     * 类级定位遍历 TestClassStat，只使用实际存在的数据。
     */
    private static void buildStaticIssues(TestQualityStaticStats stats,
                                           List<com.testpilot.dto.QualityIssue> issues) {
        int total = stats.getTotalTestMethods();
        if (total <= 0) return;

        int empty = stats.getEmptyTestMethods();
        int noAssertion = stats.getNoAssertionTestMethods();
        int skipped = stats.getSkippedTestMethods();

        // EMPTY_TEST（全局 + 类级）
        if (empty > 0) {
            issues.add(new com.testpilot.dto.QualityIssue(
                    "EMPTY_TEST",
                    "存在 " + empty + " 个空测试方法（方法体无实际语句）",
                    "请为这些测试补全断言与验证逻辑",
                    "空测试占比 " + String.format("%.1f%%", 100.0 * empty / total),
                    null
            ));
            // 类级定位
            for (com.testpilot.dto.TestClassStat cs : stats.getClassStats()) {
                if (cs != null && cs.getEmptyTestMethodCount() > 0) {
                    issues.add(new com.testpilot.dto.QualityIssue(
                            "EMPTY_TEST",
                            cs.getClassName() + " 有 " + cs.getEmptyTestMethodCount() + " 个空测试",
                            "请为这些测试补全断言与验证逻辑",
                            "emptyTestMethodCount=" + cs.getEmptyTestMethodCount(),
                            cs.getFilePath()
                    ));
                }
            }
        }

        // NO_ASSERTION（去重：empty ⊆ noAssertion，单独评价"有语句无断言"）
        int nonAssertionOnly = Math.max(0, noAssertion - empty);
        if (nonAssertionOnly > 0) {
            issues.add(new com.testpilot.dto.QualityIssue(
                    "NO_ASSERTION",
                    "存在 " + nonAssertionOnly + " 个有语句但无断言的测试方法",
                    "请添加 JUnit Assertions.assert* 调用验证业务行为",
                    "有语句无断言占比 " + String.format("%.1f%%", 100.0 * nonAssertionOnly / total),
                    null
            ));
            for (com.testpilot.dto.TestClassStat cs : stats.getClassStats()) {
                if (cs != null && cs.getEmptyTestMethodCount() < cs.getNoAssertionTestMethodCount()) {
                    // noAssertion - empty > 0 表示该类有"有语句但无断言"的测试
                    int clsNonAssertOnly = cs.getNoAssertionTestMethodCount() - cs.getEmptyTestMethodCount();
                    if (clsNonAssertOnly > 0) {
                        issues.add(new com.testpilot.dto.QualityIssue(
                                "NO_ASSERTION",
                                cs.getClassName() + " 有 " + clsNonAssertOnly + " 个测试无断言",
                                "请添加断言调用验证业务行为",
                                "noAssertionTestMethodCount - emptyTestMethodCount=" + clsNonAssertOnly,
                                cs.getFilePath()
                        ));
                    }
                }
            }
        }

        // SKIPPED_TEST
        if (skipped > 0) {
            issues.add(new com.testpilot.dto.QualityIssue(
                    "SKIPPED_TEST",
                    "存在 " + skipped + " 个被 @Ignore/@Disabled 跳过的测试方法",
                    "请评估跳过测试是否应删除或实现（跳过测试不计入有效性失败，仅作为规范性问题）",
                    "跳过测试占比 " + String.format("%.1f%%", 100.0 * skipped / total),
                    null
            ));
        }
    }

    /**
     * 密度类 issues：LOW_METHOD_DENSITY / LOW_ASSERTION_DENSITY。
     * 需要 sufficiency 维度 available=true 且分数 < 50。
     */
    private static void buildDensityIssues(TestQualityStaticStats stats,
                                            List<QualityDimension> dims,
                                            List<com.testpilot.dto.QualityIssue> issues) {
        QualityDimension suf = findDim(dims, QualityDimension.SUFFICIENCY);
        if (suf == null || !suf.isAvailable() || suf.getScore() == null) return;

        int classCount = stats.getTestClassCount();
        int methods = stats.getTotalTestMethods();
        int assertions = stats.getTotalAssertions();

        // LOW_METHOD_DENSITY：方法密度分 < 50 → avgMethodsPerClass < 2.5
        if (classCount > 0) {
            double avgMethods = (double) methods / classCount;
            double methodScore = clamp(avgMethods / TARGET_METHODS_PER_CLASS * 100.0, 0.0, 100.0);
            if (methodScore < 50.0) {
                issues.add(new com.testpilot.dto.QualityIssue(
                        "LOW_METHOD_DENSITY",
                        "测试方法密度偏低（平均每类 " + String.format("%.1f", avgMethods)
                                + " 个测试方法，目标 " + TARGET_METHODS_PER_CLASS + "）",
                        "建议为每个被测方法补充更多测试用例覆盖不同分支和边界",
                        "方法密度分=" + String.format("%.1f", methodScore),
                        null
                ));
            }
        }

        // LOW_ASSERTION_DENSITY：断言密度分 < 50 → avgAssertionsPerTest < 1.25
        if (methods > 0) {
            double avgAssertions = (double) assertions / methods;
            double assertionScore = clamp(avgAssertions / TARGET_ASSERTIONS_PER_TEST * 100.0, 0.0, 100.0);
            if (assertionScore < 50.0) {
                issues.add(new com.testpilot.dto.QualityIssue(
                        "LOW_ASSERTION_DENSITY",
                        "断言密度偏低（平均每方法 " + String.format("%.1f", avgAssertions)
                                + " 个断言，目标 " + TARGET_ASSERTIONS_PER_TEST + "）",
                        "建议为每个测试方法补充更多断言验证不同边界条件和返回值",
                        "断言密度分=" + String.format("%.1f", assertionScore),
                        null
                ));
            }
        }
    }

    /** 覆盖率类 issues：LOW_BRANCH / LOW_LINE。依赖 COVERAGE_GENERATED 状态。 */
    private static void buildCoverageIssues(CoverageResult cov,
                                             List<com.testpilot.dto.QualityIssue> issues) {
        if (cov == null || !COV_GENERATED.equals(cov.getCoverageStatus())) return;
        List<CoverageMetric> metrics = cov.getMetrics();
        if (metrics == null) return;

        CoverageMetric line = findMetric(metrics, "LINE");
        CoverageMetric branch = findMetric(metrics, "BRANCH");

        if (line != null && line.isAvailable() && line.getPercentage() != null
                && line.getPercentage() < 60.0) {
            issues.add(new com.testpilot.dto.QualityIssue(
                    "LOW_LINE",
                    "行覆盖率偏低（" + String.format("%.1f%%", line.getPercentage())
                            + "，建议阈值 60%）",
                    "建议增加测试用例覆盖更多业务路径，提高行覆盖率",
                    "LINE percentage=" + String.format("%.1f", line.getPercentage()),
                    null
            ));
        }

        if (branch != null && branch.isAvailable() && branch.getPercentage() != null
                && branch.getPercentage() < 30.0) {
            issues.add(new com.testpilot.dto.QualityIssue(
                    "LOW_BRANCH",
                    "分支覆盖率偏低（" + String.format("%.1f%%", branch.getPercentage())
                            + "，建议阈值 30%）",
                    "建议为关键条件语句添加边界值和正反例测试，提高分支覆盖率",
                    "BRANCH percentage=" + String.format("%.1f", branch.getPercentage()),
                    null
            ));
        }
    }

    /** 执行状态类 issues：TEST_FAILED / COMPILE_ERROR。 */
    private static void buildExecutionIssues(TestExecutionResult exec,
                                              List<com.testpilot.dto.QualityIssue> issues) {
        if (exec == null || exec.getStatus() == null) return;
        String status = exec.getStatus();

        if (STATUS_FAILED.equals(status) && exec.getFailedTests() > 0) {
            issues.add(new com.testpilot.dto.QualityIssue(
                    "TEST_FAILED",
                    "有 " + exec.getFailedTests() + " 个测试断言失败",
                    "请检查业务逻辑实现是否正确，或修复测试断言使其匹配预期行为",
                    exec.getFailedTests() + " 个失败 / " + exec.getTotalTests() + " 个执行",
                    null
            ));
        }

        if ("COMPILE_ERROR".equals(status)) {
            issues.add(new com.testpilot.dto.QualityIssue(
                    "COMPILE_ERROR",
                    "项目无法编译（主源码或测试源码至少有一处编译错误）",
                    "请先修复编译错误后再生成和评估测试",
                    "exitCode=" + exec.getExitCode(),
                    null
            ));
        }
    }

    private static QualityDimension findDim(List<QualityDimension> dims, String type) {
        if (dims == null || type == null) return null;
        for (QualityDimension d : dims) {
            if (d != null && type.equals(d.getType())) return d;
        }
        return null;
    }

    // ============ 私有辅助方法 ============

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static CoverageMetric findMetric(List<CoverageMetric> metrics, String type) {
        if (metrics == null || type == null) return null;
        for (CoverageMetric m : metrics) {
            if (m != null && type.equals(m.getType())) return m;
        }
        return null;
    }

    private static QualityDimension unavailableDim(String type, String display, double weight, String reason) {
        return new QualityDimension(type, display, false, null, reason, weight);
    }
}
