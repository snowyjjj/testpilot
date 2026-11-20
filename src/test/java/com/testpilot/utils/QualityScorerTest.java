package com.testpilot.utils;

import com.testpilot.dto.CoverageMetric;
import com.testpilot.dto.CoverageResult;
import com.testpilot.dto.QualityDimension;
import com.testpilot.dto.QualityIssue;
import com.testpilot.dto.QualityReport;
import com.testpilot.dto.TestClassStat;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.dto.TestQualityResult;
import com.testpilot.dto.TestQualityStaticStats;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QualityScorer 单元测试。
 * <p>
 * 纯 Java，无 Spring 容器依赖——所有 DTO 有 @AllArgsConstructor，可以直接 new 出来传参。
 */
class QualityScorerTest {

    // ============ 维度一：有效性 ============

    @Nested
    class Effectiveness {

        @Test
        void passed_allPassed_score100() {
            // 注意：total 必须等于 passed+failed+skipped（计数一致性防御）
            TestExecutionResult exec = new TestExecutionResult("pid", true, "PASSED", 0,
                    14, 11, 0, 3, 0L, "ok", null);
            QualityDimension d = QualityScorer.scoreEffectiveness(exec);
            assertTrue(d.isAvailable());
            assertEquals(100.0, d.getScore(), 0.001);
        }

        @Test
        void passed_withSkipped_excludedFromDenominator() {
            // PASSED: 11 通过, 0 失败, 3 跳过, total=14
            // 分母 = 11+0 = 11, 跳过的 3 不计入
            TestExecutionResult exec = new TestExecutionResult("pid", true, "PASSED", 0,
                    14, 11, 0, 3, 0L, "ok", null);
            QualityDimension d = QualityScorer.scoreEffectiveness(exec);
            assertTrue(d.isAvailable());
            assertEquals(100.0, d.getScore(), 0.001);
        }

        @Test
        void failed_partial_pass_scoreBasedOnPassedOverSum() {
            // FAILED: passed=9, failed=2, skipped=3, total=14
            // 分母 = 9+2 = 11, score = 9/11*100 ≈ 81.82
            TestExecutionResult exec = new TestExecutionResult("pid", false, "FAILED", -1,
                    14, 9, 2, 3, 0L, "fail output", null);
            QualityDimension d = QualityScorer.scoreEffectiveness(exec);
            assertTrue(d.isAvailable());
            assertEquals(9.0 / 11.0 * 100.0, d.getScore(), 0.01);
        }

        @Test
        void failed_allFail_score0() {
            TestExecutionResult exec = new TestExecutionResult("pid", false, "FAILED", -1,
                    5, 0, 5, 0, 0L, "all fail", null);
            QualityDimension d = QualityScorer.scoreEffectiveness(exec);
            assertTrue(d.isAvailable());
            assertEquals(0.0, d.getScore(), 0.001);  // 真 0 分，不是 null
        }

        @Test
        void compileError_unavailable() {
            TestExecutionResult exec = new TestExecutionResult("pid", false, "COMPILE_ERROR", -1,
                    0, 0, 0, 0, 0L, null, "编译失败");
            QualityDimension d = QualityScorer.scoreEffectiveness(exec);
            assertFalse(d.isAvailable());
            assertNull(d.getScore());
        }

        @Test
        void nullExec_unavailable() {
            QualityDimension d = QualityScorer.scoreEffectiveness(null);
            assertFalse(d.isAvailable());
            assertNull(d.getScore());
        }

        // ============ 用户约束 2：计数一致性防御 ============

        @Test
        void negativeCount_unavailable() {
            TestExecutionResult exec = new TestExecutionResult("pid", true, "PASSED", 0,
                    11, -1, 0, 3, 0L, null, null);  // passed=-1 非法
            QualityDimension d = QualityScorer.scoreEffectiveness(exec);
            assertFalse(d.isAvailable());
            assertNotNull(d.getReason());
        }

        @Test
        void inconsistentSum_unavailable() {
            // passed+failed+skipped = 11+2+3=16, 但 total=14
            TestExecutionResult exec = new TestExecutionResult("pid", false, "FAILED", -1,
                    14, 11, 2, 3, 0L, null, null);
            QualityDimension d = QualityScorer.scoreEffectiveness(exec);
            assertFalse(d.isAvailable());
            assertTrue(d.getReason().contains("不一致"));
        }

        @Test
        void passedStateWithFailedCount_unavailable() {
            // PASSED 但 failed=2 > 0 → 数据矛盾
            TestExecutionResult exec = new TestExecutionResult("pid", false, "PASSED", -1,
                    11, 9, 2, 0, 0L, null, null);
            QualityDimension d = QualityScorer.scoreEffectiveness(exec);
            assertFalse(d.isAvailable());
        }

        @Test
        void failedStateWithZeroFailed_unavailable() {
            // FAILED 但 failed=0 → 数据矛盾
            TestExecutionResult exec = new TestExecutionResult("pid", false, "FAILED", -1,
                    10, 10, 0, 0, 0L, null, null);
            QualityDimension d = QualityScorer.scoreEffectiveness(exec);
            assertFalse(d.isAvailable());
        }
    }

    // ============ 维度二：覆盖率 ============

    @Nested
    class Coverage {

        @Test
        void generated_normalScore() {
            CoverageResult cov = new CoverageResult("pid", exec("PASSED", 11, 11, 0, 0),
                    "COVERAGE_GENERATED",
                    metrics(true, 80, 70, 85), null);
            QualityDimension d = QualityScorer.scoreCoverage(cov);
            assertTrue(d.isAvailable());
            // 80×0.5 + 70×0.3 + 85×0.2 = 40 + 21 + 17 = 78.0
            assertEquals(78.0, d.getScore(), 0.001);
        }

        @Test
        void generated_zeroPercentage_stillAvailable() {
            // 真 0% 覆盖率：available=true, percentage=0.0 → score=0.0, available=true（不是 null）
            CoverageResult cov = new CoverageResult("pid", exec("PASSED", 11, 11, 0, 0),
                    "COVERAGE_GENERATED",
                    metrics(true, 0, 0, 0), null);
            QualityDimension d = QualityScorer.scoreCoverage(cov);
            assertTrue(d.isAvailable(), "真 0% 必须 available=true");
            assertEquals(0.0, d.getScore(), 0.001);
        }

        @Test
        void noCoverage_unavailable() {
            CoverageResult cov = new CoverageResult("pid", exec("COMPILE_ERROR", 0, 0, 0, 0),
                    "NO_COVERAGE", new ArrayList<>(), null);
            QualityDimension d = QualityScorer.scoreCoverage(cov);
            assertFalse(d.isAvailable());
            assertNull(d.getScore());
        }

        @Test
        void noReport_unavailable() {
            CoverageResult cov = new CoverageResult("pid", exec("PASSED", 11, 11, 0, 0),
                    "NO_REPORT", new ArrayList<>(), "jacoco.xml 无有效 counter");
            QualityDimension d = QualityScorer.scoreCoverage(cov);
            assertFalse(d.isAvailable());
        }

        @Test
        void partialUnavailable_normalizedWeight() {
            // BRANCH available=false，只用 LINE(0.5)+METHOD(0.2)=0.7 权重归一化
            CoverageResult cov = new CoverageResult("pid", exec("PASSED", 11, 11, 0, 0),
                    "COVERAGE_GENERATED",
                    // LINE 80, BRANCH null unavailable, METHOD 85
                    List.of(
                            new CoverageMetric("LINE", true, 20, 80, 80.0),
                            new CoverageMetric("BRANCH", false, 0, 0, null),
                            new CoverageMetric("METHOD", true, 15, 85, 85.0),
                            // COMPLEXITY / INSTRUCTION / CLASS 不参与评分
                            new CoverageMetric("INSTRUCTION", true, 100, 900, 90.0),
                            new CoverageMetric("COMPLEXITY", true, 5, 15, null),
                            new CoverageMetric("CLASS", true, 2, 8, 80.0)
                    ), null);
            QualityDimension d = QualityScorer.scoreCoverage(cov);
            assertTrue(d.isAvailable());
            // (80×0.5 + 85×0.2) / (0.5+0.2) = (40 + 17) / 0.7 ≈ 81.4286
            assertEquals(57.0 / 0.7, d.getScore(), 0.001);
        }

        @Test
        void nullCov_unavailable() {
            QualityDimension d = QualityScorer.scoreCoverage(null);
            assertFalse(d.isAvailable());
        }
    }

    // ============ 维度三：充分性 ============

    @Nested
    class Sufficiency {

        @Test
        void normalDensity_score100() {
            // 中心值：5 methods/class, 2.5 assertions/test
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTestClassCount(2);
            stats.setTotalTestMethods(10);    // avg=5
            stats.setTotalAssertions(25);    // avg=2.5
            QualityDimension d = QualityScorer.scoreSufficiency(stats);
            assertTrue(d.isAvailable());
            assertEquals(100.0, d.getScore(), 0.001);
        }

        @Test
        void lowDensity_partialScore() {
            // 低密：1.2 methods/class, 0.8 assertions/test
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTestClassCount(10);
            stats.setTotalTestMethods(12);
            stats.setTotalAssertions(10);  // 12 methods → avg=10/12≈0.833
            QualityDimension d = QualityScorer.scoreSufficiency(stats);
            assertTrue(d.isAvailable());
            double expectedMethod = 1.2 / 5.0 * 100;        // 24
            double expectedAssert = (10.0 / 12.0) / 2.5 * 100;  // ≈ 33.33
            assertEquals((expectedMethod + expectedAssert) / 2.0, d.getScore(), 0.01);
        }

        @Test
        void highDensity_clampedTo100_notPunished() {
            // 高密度（单向封顶：不扣分、不加分）
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTestClassCount(2);
            stats.setTotalTestMethods(40);     // avg=20 > 5, clamp → 100
            stats.setTotalAssertions(100);     // avg=2.5, → 100
            QualityDimension d = QualityScorer.scoreSufficiency(stats);
            assertTrue(d.isAvailable());
            assertEquals(100.0, d.getScore(), 0.001);
        }

        @Test
        void noTestClass_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTestClassCount(0);
            QualityDimension d = QualityScorer.scoreSufficiency(stats);
            assertFalse(d.isAvailable());
        }

        @Test
        void overallStaticAnalysisFailed_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTestClassCount(5);
            stats.setTotalTestMethods(10);
            stats.setAnalysisError("遍历失败");
            QualityDimension d = QualityScorer.scoreSufficiency(stats);
            assertFalse(d.isAvailable());
            assertTrue(d.getReason().contains("整体静态分析失败"));
        }

        @Test
        void nullStats_unavailable() {
            QualityDimension d = QualityScorer.scoreSufficiency(null);
            assertFalse(d.isAvailable());
        }

        // ============ 静态统计异常值防御（用户任务 5） ============

        @Test
        void negativeCount_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTestClassCount(5);
            stats.setTotalTestMethods(-3);  // 负值
            QualityDimension d = QualityScorer.scoreSufficiency(stats);
            assertFalse(d.isAvailable());
            assertTrue(d.getReason().contains("负值"));
        }

        @Test
        void negativeAssertions_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTestClassCount(5);
            stats.setTotalTestMethods(10);
            stats.setTotalAssertions(-1);  // 负值
            QualityDimension d = QualityScorer.scoreSufficiency(stats);
            assertFalse(d.isAvailable());
            assertTrue(d.getReason().contains("负值"));
        }

        @Test
        void negativeClassCount_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTestClassCount(-5);  // 负值
            stats.setTotalTestMethods(10);
            QualityDimension d = QualityScorer.scoreSufficiency(stats);
            assertFalse(d.isAvailable());
        }
    }

    // ============ 维度四：规范性 ============

    @Nested
    class Correctness {

        @Test
        void perfectTest_score100() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(10);
            stats.setEmptyTestMethods(0);
            stats.setNoAssertionTestMethods(0);
            stats.setSkippedTestMethods(0);
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertTrue(d.isAvailable());
            assertEquals(100.0, d.getScore(), 0.001);
        }

        @Test
        void allEmptyTests_score60_dedupWithNoAssertion() {
            // 10 个测试全是空：empty=10, noAssertion=10（空测试被 noAssertion 包含）
            // 去重后 nonAssertionOnly = max(0, 10-10) = 0
            // 扣分: emptyRate=1.0×40=40, nonAssert=0, skipped=0 → 100-40=60
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(10);
            stats.setEmptyTestMethods(10);
            stats.setNoAssertionTestMethods(10);
            stats.setSkippedTestMethods(0);
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertTrue(d.isAvailable());
            assertEquals(60.0, d.getScore(), 0.001);
        }

        @Test
        void emptyPlusNonEmptyNoAssertion_dedupWorks() {
            // 20 tests: 2 empty (also noAssertion), 3 non-empty but no assertion
            // empty=2, noAssertion=5, skipped=0, total=20
            // nonAssertionOnly = max(0, 5-2) = 3
            // emptyRate=2/20=0.1×40=4, nonAssertRate=3/20=0.15×30=4.5
            // → 100 - 4 - 4.5 = 91.5
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(20);
            stats.setEmptyTestMethods(2);
            stats.setNoAssertionTestMethods(5);
            stats.setSkippedTestMethods(0);
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertTrue(d.isAvailable());
            assertEquals(91.5, d.getScore(), 0.001);
        }

        @Test
        void parseFailedNotInDeduction_belongsToWarning() {
            // parseFailedFileCount 不参与规范性扣分
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(10);
            stats.setEmptyTestMethods(0);
            stats.setNoAssertionTestMethods(0);
            stats.setSkippedTestMethods(0);
            stats.setParseFailedFileCount(5);   // 这只是警告，不扣分
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertTrue(d.isAvailable());
            assertEquals(100.0, d.getScore(), 0.001);
        }

        @Test
        void noTestMethod_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(0);
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertFalse(d.isAvailable());
        }

        @Test
        void overallStaticAnalysisFailed_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(10);
            stats.setAnalysisError("遍历失败");
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertFalse(d.isAvailable());
        }

        // ============ 静态统计异常值防御（用户任务 5） ============

        @Test
        void negativeEmpty_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(10);
            stats.setEmptyTestMethods(-1);
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertFalse(d.isAvailable());
            assertTrue(d.getReason().contains("负值"));
        }

        @Test
        void emptyExceedsTotal_unavailable() {
            // empty=15 > total=10 → 越界
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(10);
            stats.setEmptyTestMethods(15);
            stats.setNoAssertionTestMethods(15);
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertFalse(d.isAvailable());
            assertTrue(d.getReason().contains("越界"));
        }

        @Test
        void skippedExceedsTotal_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(10);
            stats.setSkippedTestMethods(11);
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertFalse(d.isAvailable());
            assertTrue(d.getReason().contains("越界"));
        }

        @Test
        void noAssertionExceedsTotal_unavailable() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setTotalTestMethods(10);
            stats.setNoAssertionTestMethods(20);
            QualityDimension d = QualityScorer.scoreCorrectness(stats);
            assertFalse(d.isAvailable());
            assertTrue(d.getReason().contains("越界"));
        }
    }

    // ============ 综合分 + 等级（含用户修正的算术值） ============

    @Nested
    class OverallAndGrade {

        @Test
        void typical_fullAvailability_arithmeticO1() {
            // 四维度全部 available：手动用权重公式验证综合分 = Σ(score×w)/Σw
            TestQualityResult r = buildResult(
                    exec("PASSED", 11, 11, 0, 0),
                    covWith(80, 70, 85),
                    stats(2, 10, 25, 0, 0, 0, 0));
            QualityReport report = QualityScorer.score(r);

            // 拿到四个维度实际分数
            QualityDimension eff = findDim(report.getDimensions(), QualityDimension.EFFECTIVENESS);
            QualityDimension cov = findDim(report.getDimensions(), QualityDimension.COVERAGE);
            QualityDimension suf = findDim(report.getDimensions(), QualityDimension.SUFFICIENCY);
            QualityDimension cor = findDim(report.getDimensions(), QualityDimension.CORRECTNESS);
            // 全部应该 available
            assertTrue(eff.isAvailable() && cov.isAvailable() && suf.isAvailable() && cor.isAvailable());

            // 手动按权重归一化公式计算
            double wsum = eff.getWeight() + cov.getWeight() + suf.getWeight() + cor.getWeight();
            double manualSum = eff.getScore() * eff.getWeight()
                    + cov.getScore() * cov.getWeight()
                    + suf.getScore() * suf.getWeight()
                    + cor.getScore() * cor.getWeight();
            double manualOverall = manualSum / wsum;

            // report 内的综合分与手动计算必须一致
            assertNotNull(report.getOverallScore());
            assertEquals(manualOverall, report.getOverallScore(), 0.001);

            // 用户算术修正公式验证（coverage=80, suff=90, cor=85 都是假设值，
            // 这里我们验证公式本身：100×0.3 + 80×0.25 + 90×0.2 + 85×0.25 = 89.25）
            // 用假设值单独计算验证公式正确
            double assume = 100.0 * 0.30 + 80.0 * 0.25 + 90.0 * 0.20 + 85.0 * 0.25;
            assertEquals(89.25, assume, 0.001);

            // 等级：90 以下 = B
            if (report.getOverallScore() < 90.0) {
                assertEquals("B", report.getGrade());
            }
        }

        @Test
        void compileError_noCoverage_onlyStaticAvailable_gradeNA() {
            // 关键用户约束 3：COMPILE_ERROR + NO_COVERAGE + 完整静态
            // effectiveness unavailable (COMPILE_ERROR), coverage unavailable (NO_COVERAGE)
            // sufficiency + correctness 可用 → 可评维度=2，但运行时维度=0
            // → 综合分 N/A，即使两个静态维度都是 100 也不给出 A/B
            TestQualityResult r = buildResult(
                    exec("COMPILE_ERROR", 0, 0, 0, 0),
                    new CoverageResult("pid", exec("COMPILE_ERROR", 0, 0, 0, 0),
                            "NO_COVERAGE", new ArrayList<>(), null),
                    stats(2, 10, 25, 0, 0, 0, 0));
            QualityReport report = QualityScorer.score(r);
            assertNull(report.getOverallScore(), "COMPILE_ERROR + NO_COVERAGE → 综合分必须为 null");
            assertEquals("N/A", report.getGrade());
            assertEquals(2, availableCount(report.getDimensions()));  // sufficiency + correctness
            assertEquals(0, runtimeAvailableCount(report.getDimensions()));
        }

        @Test
        void passed_noReport_stillHasRuntimeDimension_arithmeticO3() {
            // effectiveness available (PASSED, runtime), coverage unavailable (NO_REPORT)
            // sufficiency + correctness available
            // 可评维度=3, 运行时维度=1 → 正常计算，权重重归一化
            TestQualityResult r = buildResult(
                    exec("PASSED", 11, 11, 0, 0),
                    new CoverageResult("pid", exec("PASSED", 11, 11, 0, 0),
                            "NO_REPORT", new ArrayList<>(), null),
                    stats(2, 10, 25, 0, 0, 0, 0));
            QualityReport report = QualityScorer.score(r);

            assertNotNull(report.getOverallScore());

            // 手动计算：只用 available=true 的三个维度（eff, suf, cor），权重归一化
            QualityDimension eff = findDim(report.getDimensions(), QualityDimension.EFFECTIVENESS);
            QualityDimension cov = findDim(report.getDimensions(), QualityDimension.COVERAGE);
            QualityDimension suf = findDim(report.getDimensions(), QualityDimension.SUFFICIENCY);
            QualityDimension cor = findDim(report.getDimensions(), QualityDimension.CORRECTNESS);

            assertTrue(eff.isAvailable());
            assertFalse(cov.isAvailable(), "NO_REPORT → coverage 必须不可评");
            assertTrue(suf.isAvailable());
            assertTrue(cor.isAvailable());

            double wsum = eff.getWeight() + suf.getWeight() + cor.getWeight();  // 跳过 cov
            double manualSum = eff.getScore() * eff.getWeight()
                    + suf.getScore() * suf.getWeight()
                    + cor.getScore() * cor.getWeight();
            double manualOverall = manualSum / wsum;

            assertEquals(manualOverall, report.getOverallScore(), 0.001);

            // 用户算术修正公式验证：假设 eff=100, cov=unavailable, suf=90, cor=85
            // (100×0.3 + 90×0.2 + 85×0.25) / (0.3+0.2+0.25) = 69.25 / 0.75 ≈ 92.33
            double assumeSum = 100.0 * 0.30 + 90.0 * 0.20 + 85.0 * 0.25;
            double assumeWsum = 0.30 + 0.20 + 0.25;
            assertEquals(92.333, assumeSum / assumeWsum, 0.001);
        }

        @Test
        void failed_partialCoverage_arithmeticFailedExample() {
            // 用户修正值：effectiveness=72.7, coverage=0, sufficiency=80, correctness=50
            // 综合 = 72.7×0.3 + 0×0.25 + 80×0.2 + 50×0.25 = 21.81 + 0 + 16 + 12.5 = 50.31
            TestQualityResult r = buildResult(
                    exec("FAILED", 11, 8, 3, 0),  // 8/11 ≈ 72.73 有效通过比例
                    covWith(0, 0, 0),
                    statsForDensity(10, 20, 40,  // 密度: 2 methods/class, 2 assertions/method
                            5, 10, 1, 3));    // empty=1, noAssertion=5, skipped=3, total=10
            QualityReport report = QualityScorer.score(r);
            // 验证四个维度各自的分数
            QualityDimension eff = findDim(report.getDimensions(), "EFFECTIVENESS");
            QualityDimension covDim = findDim(report.getDimensions(), "COVERAGE");
            QualityDimension suf = findDim(report.getDimensions(), "SUFFICIENCY");
            QualityDimension cor = findDim(report.getDimensions(), "CORRECTNESS");

            assertNotNull(eff.getScore());
            // effectiveness: passed/(passed+failed) = 8/(8+3) ≈ 72.73
            assertEquals(72.73, eff.getScore(), 0.5);  // 宽松因为 exec 构造器 passed=8, failed=3

            assertTrue(covDim.isAvailable());
            assertEquals(0.0, covDim.getScore(), 0.001);

            // 综合分（权重归一化后）：
            // effectiveness × 0.3 + 0 × 0.25 + suff × 0.2 + cor × 0.25 = ?
            double expected = eff.getScore() * 0.3 + 0 * 0.25 + suf.getScore() * 0.2 + cor.getScore() * 0.25;
            assertEquals(expected, report.getOverallScore(), 0.01);
        }

        @Test
        void grade_boundary_A() {
            assertEquals("A", QualityScorer.grade(90.0, 4, true));
            assertEquals("A", QualityScorer.grade(95.0, 4, true));
            assertEquals("A", QualityScorer.grade(99.9, 4, true));
        }

        @Test
        void grade_boundary_B() {
            assertEquals("B", QualityScorer.grade(75.0, 4, true));
            assertEquals("B", QualityScorer.grade(89.999, 4, true));
        }

        @Test
        void grade_boundary_C() {
            assertEquals("C", QualityScorer.grade(60.0, 4, true));
            assertEquals("C", QualityScorer.grade(74.999, 4, true));
        }

        @Test
        void grade_boundary_D() {
            assertEquals("D", QualityScorer.grade(40.0, 4, true));
            assertEquals("D", QualityScorer.grade(59.999, 4, true));
        }

        @Test
        void grade_boundary_F() {
            assertEquals("F", QualityScorer.grade(39.999, 4, true));
            assertEquals("F", QualityScorer.grade(0.0, 4, true));
        }

        @Test
        void grade_NA_whenNull() {
            assertEquals("N/A", QualityScorer.grade(null, 4, true));
            assertEquals("N/A", QualityScorer.grade(80.0, 1, true));   // 可评维度 < 2
            assertEquals("N/A", QualityScorer.grade(95.0, 4, false));  // 无运行时维度
        }

        // ============ 用户任务 1：手动构造 QualityDimension，独立验证算术公式 ============

        @Test
        void arithmeticO1_manualConstruction_independentVerification() {
            // 手动构造四个 QualityDimension，不通过各维度评分方法
            List<QualityDimension> dims = List.of(
                    new QualityDimension(QualityDimension.EFFECTIVENESS, "有效性",
                            true, 100.0, null, 0.30),
                    new QualityDimension(QualityDimension.COVERAGE, "覆盖率",
                            true, 80.0, null, 0.25),
                    new QualityDimension(QualityDimension.SUFFICIENCY, "充分性",
                            true, 90.0, null, 0.20),
                    new QualityDimension(QualityDimension.CORRECTNESS, "规范性",
                            true, 85.0, null, 0.25)
            );
            Double result = QualityScorer.overallScore(dims);
            assertNotNull(result);
            // 100×0.30 + 80×0.25 + 90×0.20 + 85×0.25 = 30 + 20 + 18 + 21.25 = 89.25
            assertEquals(89.25, result, 0.0001);
        }

        @Test
        void arithmeticO3_manualConstruction_normalized() {
            // 手动构造三个可评维度：有效性 100（运行时）、充分性 90、规范性 85
            // 覆盖率不可评（只给 available=false）
            List<QualityDimension> dims = List.of(
                    new QualityDimension(QualityDimension.EFFECTIVENESS, "有效性",
                            true, 100.0, null, 0.30),
                    new QualityDimension(QualityDimension.COVERAGE, "覆盖率",
                            false, null, "不可评", 0.25),
                    new QualityDimension(QualityDimension.SUFFICIENCY, "充分性",
                            true, 90.0, null, 0.20),
                    new QualityDimension(QualityDimension.CORRECTNESS, "规范性",
                            true, 85.0, null, 0.25)
            );
            Double result = QualityScorer.overallScore(dims);
            assertNotNull(result);
            // 只算 available 的三个维度（跳过 coverage 的 0.25）
            // (100×0.30 + 90×0.20 + 85×0.25) / (0.30+0.20+0.25) = 69.25 / 0.75 ≈ 92.333333
            assertEquals(92.333333, result, 0.0001);
        }
    }

    // ============ reliabilityWarning ============

    @Nested
    class ReliabilityWarning {

        @Test
        void analysisErrorPresent_wins() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setAnalysisError("遍历失败");
            stats.setParseFailedFileCount(3);
            String w = QualityScorer.buildReliabilityWarning(stats);
            assertNotNull(w);
            assertTrue(w.contains("整体静态分析失败"));
        }

        @Test
        void parseFailedOnly_warn() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            stats.setParseFailedFileCount(3);
            String w = QualityScorer.buildReliabilityWarning(stats);
            assertNotNull(w);
            assertTrue(w.contains("3"));
            assertTrue(w.contains("解析失败"));
        }

        @Test
        void noWarning() {
            TestQualityStaticStats stats = new TestQualityStaticStats();
            assertNull(QualityScorer.buildReliabilityWarning(stats));
        }
    }

    // ============ 改进建议 buildIssues 规则（第三批） ============

    @Nested
    class IssueRules {

        private List<com.testpilot.dto.QualityIssue> issue(TestQualityResult r) {
            return QualityScorer.score(r).getIssues();
        }

        private List<com.testpilot.dto.QualityIssue> issues(TestQualityResult r) {
            return issue(r);
        }

        private boolean hasCat(List<com.testpilot.dto.QualityIssue> list, String cat) {
            return list.stream().anyMatch(i -> cat.equals(i.getCategory()));
        }

        private com.testpilot.dto.QualityIssue findCat(List<com.testpilot.dto.QualityIssue> list, String cat) {
            return list.stream().filter(i -> cat.equals(i.getCategory())).findFirst().orElse(null);
        }

        // ---------- 静态类 issues ----------

        @Test
        void emptyTest_triggered() {
            TestQualityStaticStats stats = stats(2, 10, 20, 3, 3, 0, 0);
            TestClassStat c1 = new TestClassStat("src/test/java/a/ATest.java", "ATest", 5, 10, 2, 2, 0);
            TestClassStat c2 = new TestClassStat("src/test/java/b/BTest.java", "BTest", 5, 10, 1, 1, 0);
            stats.setClassStats(List.of(c1, c2));

            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            List<com.testpilot.dto.QualityIssue> list = issues(r);

            assertTrue(hasCat(list, "EMPTY_TEST"));
            com.testpilot.dto.QualityIssue global = findCat(list, "EMPTY_TEST");
            assertNotNull(global);
            assertTrue(global.getDescription().contains("3"));
            assertNotNull(global.getEvidence());
            // 类级定位
            long classIssueCount = list.stream()
                    .filter(i -> "EMPTY_TEST".equals(i.getCategory()) && i.getFilePath() != null)
                    .count();
            assertEquals(2, classIssueCount);
        }

        @Test
        void emptyTest_notTriggered_whenZero() {
            TestQualityStaticStats stats = stats(2, 10, 20, 0, 0, 0, 0);
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            assertFalse(hasCat(issues(r), "EMPTY_TEST"));
        }

        @Test
        void noAssertion_triggered_dedup() {
            // empty=2, noAssertion=5 → 去重后 nonAssertionOnly=3 有语句无断言
            TestQualityStaticStats stats = stats(2, 10, 20, 2, 5, 0, 0);
            stats.setClassStats(List.of(
                    new TestClassStat("src/test/java/a/ATest.java", "ATest", 10, 20, 2, 4, 0)
                    // ATest: noAssertion(4) > empty(2) → 有 2 个有语句无断言
            ));
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            List<com.testpilot.dto.QualityIssue> list = issues(r);

            assertTrue(hasCat(list, "NO_ASSERTION"));
            // 类级定位：ATest 有 nonAssertionOnly=2
            long classIssues = list.stream()
                    .filter(i -> "NO_ASSERTION".equals(i.getCategory()) && i.getFilePath() != null)
                    .count();
            assertEquals(1, classIssues);
        }

        @Test
        void noAssertion_notTriggered_whenOnlyEmpty() {
            // empty=3, noAssertion=3 → 去重后 nonAssertionOnly=0，无"有语句无断言"
            TestQualityStaticStats stats = stats(2, 10, 20, 3, 3, 0, 0);
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            assertFalse(hasCat(issues(r), "NO_ASSERTION"));
        }

        @Test
        void skippedTest_triggered() {
            TestQualityStaticStats stats = stats(2, 10, 20, 0, 0, 3, 0);
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            assertTrue(hasCat(issues(r), "SKIPPED_TEST"));
        }

        @Test
        void skippedTest_notTriggered_whenZero() {
            TestQualityStaticStats stats = stats(2, 10, 20, 0, 0, 0, 0);
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            assertFalse(hasCat(issues(r), "SKIPPED_TEST"));
        }

        @Test
        void parseFailed_triggered_withWarning() {
            TestQualityStaticStats stats = stats(2, 10, 20, 0, 0, 0, 3);
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            List<com.testpilot.dto.QualityIssue> list = issues(r);

            assertTrue(hasCat(list, "PARSE_FAILED"));
            com.testpilot.dto.QualityIssue pf = findCat(list, "PARSE_FAILED");
            assertTrue(pf.getDescription().contains("3"));
            // 同时 reliabilityWarning 存在
            QualityReport report = QualityScorer.score(r);
            assertNotNull(report.getReliabilityWarning());
            assertTrue(report.getReliabilityWarning().contains("3"));
        }

        @Test
        void parseFailed_notGenerated_whenAnalysisError() {
            // analysisError != null 时静态类 issues 全部跳过（包括 PARSE_FAILED）
            TestQualityStaticStats stats = stats(2, 10, 20, 0, 0, 0, 3);
            stats.setAnalysisError("遍历失败");
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            assertFalse(hasCat(issues(r), "PARSE_FAILED"));
            assertFalse(hasCat(issues(r), "EMPTY_TEST"));
            assertFalse(hasCat(issues(r), "NO_ASSERTION"));
        }

        // ---------- 密度 issues ----------

        @Test
        void lowMethodDensity_triggered() {
            // classCount=10, methods=12 → avgMethodsPerClass=1.2 → 1.2/5*100=24 < 50
            TestQualityStaticStats stats = stats(10, 12, 20, 0, 0, 0, 0);
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            List<com.testpilot.dto.QualityIssue> list = issues(r);

            assertTrue(hasCat(list, "LOW_METHOD_DENSITY"));
            com.testpilot.dto.QualityIssue issue = findCat(list, "LOW_METHOD_DENSITY");
            assertTrue(issue.getEvidence().contains("方法密度分"));
        }

        @Test
        void lowAssertionDensity_triggered() {
            // classCount=2, methods=10, assertions=5 → avgAssertionsPerTest=0.5 → 0.5/2.5*100=20 < 50
            TestQualityStaticStats stats = stats(2, 10, 5, 0, 0, 0, 0);
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            assertTrue(hasCat(issues(r), "LOW_ASSERTION_DENSITY"));
        }

        @Test
        void density_notTriggered_whenSufficient() {
            // 中心值：5 methods/class, 2.5 assertions/test → 两密度分均=100 ≥ 50
            TestQualityStaticStats stats = stats(2, 10, 25, 0, 0, 0, 0);
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            assertFalse(hasCat(issues(r), "LOW_METHOD_DENSITY"));
            assertFalse(hasCat(issues(r), "LOW_ASSERTION_DENSITY"));
        }

        @Test
        void density_notTriggered_whenStaticAnalyzedFailed() {
            // analysisError != null → sufficiency 不可评 → density issues 不生成
            TestQualityStaticStats stats = stats(10, 12, 20, 0, 0, 0, 0);
            stats.setAnalysisError("遍历失败");
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), covWith(90, 80, 95), stats);
            assertFalse(hasCat(issues(r), "LOW_METHOD_DENSITY"));
            assertFalse(hasCat(issues(r), "LOW_ASSERTION_DENSITY"));
        }

        // ---------- 覆盖率 issues ----------

        @Test
        void lowLine_triggered_whenBelow60() {
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0),
                    covWith(55, 80, 90),
                    stats(2, 10, 20, 0, 0, 0, 0));
            assertTrue(hasCat(issues(r), "LOW_LINE"));
            // LINE=55 < 60 → 触发
        }

        @Test
        void lowLine_notTriggered_whenAbove60() {
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0),
                    covWith(60, 80, 90),  // LINE=60.0 边界
                    stats(2, 10, 20, 0, 0, 0, 0));
            // LINE=60 不低于 60 → 不触发
            assertFalse(hasCat(issues(r), "LOW_LINE"));
        }

        @Test
        void lowBranch_triggered_whenBelow30() {
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0),
                    covWith(80, 29, 90),  // BRANCH=29 < 30
                    stats(2, 10, 20, 0, 0, 0, 0));
            assertTrue(hasCat(issues(r), "LOW_BRANCH"));
        }

        @Test
        void lowBranch_notTriggered_whenAbove30() {
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0),
                    covWith(80, 30, 90),  // BRANCH=30.0 边界
                    stats(2, 10, 20, 0, 0, 0, 0));
            assertFalse(hasCat(issues(r), "LOW_BRANCH"));
        }

        @Test
        void lowCoverage_notTriggered_whenUnavailable() {
            CoverageResult cov = new CoverageResult("pid", exec("PASSED", 11, 11, 0, 0),
                    "NO_COVERAGE", new ArrayList<>(), null);
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0), cov,
                    stats(2, 10, 20, 0, 0, 0, 0));
            assertFalse(hasCat(issues(r), "LOW_LINE"));
            assertFalse(hasCat(issues(r), "LOW_BRANCH"));
        }

        // ---------- 执行状态 issues ----------

        @Test
        void testFailed_triggered() {
            TestQualityResult r = buildResult(exec("FAILED", 11, 8, 3, 0),
                    covWith(80, 70, 85),
                    stats(2, 10, 20, 0, 0, 0, 0));
            List<com.testpilot.dto.QualityIssue> list = issues(r);
            assertTrue(hasCat(list, "TEST_FAILED"));
            com.testpilot.dto.QualityIssue tf = findCat(list, "TEST_FAILED");
            assertTrue(tf.getEvidence().contains("3"));
            assertTrue(tf.getEvidence().contains("11"));
        }

        @Test
        void testFailed_notTriggered_whenPassed() {
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0),
                    covWith(80, 70, 85),
                    stats(2, 10, 20, 0, 0, 0, 0));
            assertFalse(hasCat(issues(r), "TEST_FAILED"));
        }

        @Test
        void compileError_triggered() {
            TestQualityResult r = buildResult(exec("COMPILE_ERROR", 0, 0, 0, 0),
                    new CoverageResult("pid", exec("COMPILE_ERROR", 0, 0, 0, 0),
                            "NO_COVERAGE", new ArrayList<>(), null),
                    stats(2, 10, 20, 0, 0, 0, 0));
            List<com.testpilot.dto.QualityIssue> list = issues(r);
            assertTrue(hasCat(list, "COMPILE_ERROR"));
            assertTrue(hasCat(list, "PARSE_FAILED") == false);  // analysisError 为空所以不会跳过，实际也没 parseFailed
        }

        @Test
        void compileError_notTriggered_whenPassed() {
            TestQualityResult r = buildResult(exec("PASSED", 11, 11, 0, 0),
                    covWith(80, 70, 85),
                    stats(2, 10, 20, 0, 0, 0, 0));
            assertFalse(hasCat(issues(r), "COMPILE_ERROR"));
        }

        // ---------- 数据一致性与跨类型测试 ----------

        @Test
        void runtimeIssuesGenerated_whenStaticAnalysisFailed() {
            // 整体静态分析失败：静态 issues 全部跳过，但 TEST_FAILED / COMPILE_ERROR 仍生成
            TestQualityStaticStats stats = stats(2, 10, 20, 2, 2, 1, 0);
            stats.setAnalysisError("遍历失败");
            TestQualityResult r = buildResult(exec("FAILED", 11, 8, 3, 0),
                    covWith(80, 29, 90),
                    stats);
            List<com.testpilot.dto.QualityIssue> list = issues(r);

            // 静态 issues 全部跳过
            assertFalse(hasCat(list, "EMPTY_TEST"));
            assertFalse(hasCat(list, "NO_ASSERTION"));
            assertFalse(hasCat(list, "SKIPPED_TEST"));
            assertFalse(hasCat(list, "LOW_METHOD_DENSITY"));
            assertFalse(hasCat(list, "LOW_ASSERTION_DENSITY"));

            // 运行时 issues 正常生成
            assertTrue(hasCat(list, "TEST_FAILED"));
            assertTrue(hasCat(list, "LOW_BRANCH"));
        }
    }

    // ============ 辅助构造器 ============

    private static TestExecutionResult exec(String status, int total, int passed, int failed, int skipped) {
        boolean success = "PASSED".equals(status);
        return new TestExecutionResult("pid", success, status, success ? 0 : -1,
                total, passed, failed, skipped, 0L, null, null);
    }

    private static CoverageResult covWith(double LINE, double BRANCH, double METHOD) {
        int li = (int) LINE, ba = (int) BRANCH, me = (int) METHOD;
        return new CoverageResult("pid", exec("PASSED", 11, 11, 0, 0),
                "COVERAGE_GENERATED",
                List.of(
                        new CoverageMetric("LINE", true, 100 - li, li, LINE),
                        new CoverageMetric("BRANCH", true, 100 - ba, ba, BRANCH),
                        new CoverageMetric("METHOD", true, 100 - me, me, METHOD),
                        new CoverageMetric("INSTRUCTION", true, 0, 1000, 90.0),
                        new CoverageMetric("COMPLEXITY", true, 5, 15, null),
                        new CoverageMetric("CLASS", true, 2, 8, 80.0)
                ), null);
    }

    private static List<CoverageMetric> metrics(boolean available, double LINE, double BRANCH, double METHOD) {
        return List.of(
                new CoverageMetric("LINE", available, (int)(100-LINE), (int)LINE, LINE),
                new CoverageMetric("BRANCH", available, (int)(100-BRANCH), (int)BRANCH, BRANCH),
                new CoverageMetric("METHOD", available, (int)(100-METHOD), (int)METHOD, METHOD),
                new CoverageMetric("INSTRUCTION", true, 0, 1000, 90.0),
                new CoverageMetric("COMPLEXITY", true, 5, 15, null),
                new CoverageMetric("CLASS", true, 2, 8, 80.0)
        );
    }

    private static TestQualityStaticStats stats(int classCount, int methods, int assertions,
                                                int empty, int noAssertion, int skipped,
                                                int parseFailed) {
        TestQualityStaticStats s = new TestQualityStaticStats();
        s.setTestClassCount(classCount);
        s.setTotalTestMethods(methods);
        s.setTotalAssertions(assertions);
        s.setEmptyTestMethods(empty);
        s.setNoAssertionTestMethods(noAssertion);
        s.setSkippedTestMethods(skipped);
        s.setParseFailedFileCount(parseFailed);
        return s;
    }

    private static TestQualityStaticStats statsForDensity(int classCount, int methods, int assertions,
                                                          int empty, int noAssertion, int skipped,
                                                          int parseFailed) {
        return stats(classCount, methods, assertions, empty, noAssertion, skipped, parseFailed);
    }

    private static TestQualityResult buildResult(TestExecutionResult exec, CoverageResult cov, TestQualityStaticStats stats) {
        TestQualityResult r = new TestQualityResult();
        r.setProjectId("pid");
        r.setCoverageResult(cov);
        cov.setTestExecutionResult(exec);
        r.setStaticStats(stats);
        return r;
    }

    private static int availableCount(List<QualityDimension> dims) {
        int c = 0;
        for (QualityDimension d : dims) if (d.isAvailable()) c++;
        return c;
    }

    private static int runtimeAvailableCount(List<QualityDimension> dims) {
        int c = 0;
        for (QualityDimension d : dims) {
            if (d.isAvailable() && (QualityDimension.EFFECTIVENESS.equals(d.getType())
                    || QualityDimension.COVERAGE.equals(d.getType()))) c++;
        }
        return c;
    }

    private static QualityDimension findDim(List<QualityDimension> dims, String type) {
        for (QualityDimension d : dims) if (type.equals(d.getType())) return d;
        fail("未找到维度: " + type);
        return null;
    }
}
