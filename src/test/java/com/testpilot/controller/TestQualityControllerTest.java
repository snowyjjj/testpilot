package com.testpilot.controller;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.CoverageResult;
import com.testpilot.dto.QualityDimension;
import com.testpilot.dto.QualityIssue;
import com.testpilot.dto.QualityReport;
import com.testpilot.dto.TestQualityResult;
import com.testpilot.dto.TestQualityStaticStats;
import com.testpilot.service.TestQualityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TestQualityController.class)
class TestQualityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TestQualityService testQualityService;

    private TestQualityResult sampleResult() {
        TestQualityStaticStats stats = new TestQualityStaticStats();
        stats.setTestClassCount(1);
        stats.setTotalTestMethods(2);
        CoverageResult cov = new CoverageResult("p1", null, "COVERAGE_GENERATED", List.of(), null);
        TestQualityResult result = new TestQualityResult("p1", stats, cov);

        // 6.3 qualityReport 集成：Controller 响应 JSON 必须包含此嵌套字段
        QualityReport report = new QualityReport();
        report.setOverallScore(85.5);
        report.setGrade("B");
        report.setDimensions(List.of(
                new QualityDimension("EFFECTIVENESS", "有效性", true, 100.0, null, 0.30),
                new QualityDimension("COVERAGE", "覆盖率", true, 80.0, null, 0.25),
                new QualityDimension("SUFFICIENCY", "充分性", true, 90.0, null, 0.20),
                new QualityDimension("CORRECTNESS", "规范性", true, 85.0, null, 0.25)
        ));
        report.setIssues(List.of(
                new QualityIssue("LOW_LINE", "行覆盖率偏低",
                        "增加测试覆盖更多业务路径", "LINE percentage=55.0", null)
        ));
        result.setQualityReport(report);
        return result;
    }

    @Test
    void evaluate_success_returnsHttp200() throws Exception {
        when(testQualityService.evaluate("p1")).thenReturn(sampleResult());

        mockMvc.perform(post("/api/projects/p1/quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").value("p1"))
                .andExpect(jsonPath("$.data.staticStats.testClassCount").value(1))
                .andExpect(jsonPath("$.data.coverageResult.coverageStatus").value("COVERAGE_GENERATED"));
    }

    @Test
    void evaluate_success_withQualityReport() throws Exception {
        when(testQualityService.evaluate("p1")).thenReturn(sampleResult());

        mockMvc.perform(post("/api/projects/p1/quality"))
                .andExpect(status().isOk())
                // qualityReport 整体存在
                .andExpect(jsonPath("$.data.qualityReport").exists())
                .andExpect(jsonPath("$.data.qualityReport.overallScore").value(85.5))
                .andExpect(jsonPath("$.data.qualityReport.grade").value("B"))
                // 四个维度
                .andExpect(jsonPath("$.data.qualityReport.dimensions").isArray())
                .andExpect(jsonPath("$.data.qualityReport.dimensions.length()").value(4))
                // issues 列表
                .andExpect(jsonPath("$.data.qualityReport.issues").isArray())
                .andExpect(jsonPath("$.data.qualityReport.issues[0].category").value("LOW_LINE"))
                .andExpect(jsonPath("$.data.qualityReport.issues[0].evidence").value("LINE percentage=55.0"))
                // 原有字段保持兼容
                .andExpect(jsonPath("$.data.projectId").value("p1"))
                .andExpect(jsonPath("$.data.staticStats.testClassCount").value(1));
    }

    @Test
    void evaluate_businessException409_returnsConflict() throws Exception {
        when(testQualityService.evaluate(anyString()))
                .thenThrow(new BusinessException(409, "该项目正在执行其他操作，请稍后重试"));

        mockMvc.perform(post("/api/projects/p1/quality"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该项目正在执行其他操作，请稍后重试"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void evaluate_businessException400_returnsBadRequest() throws Exception {
        when(testQualityService.evaluate(anyString()))
                .thenThrow(new BusinessException(400, "projectId 不能为空"));

        mockMvc.perform(post("/api/projects/p1/quality"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
