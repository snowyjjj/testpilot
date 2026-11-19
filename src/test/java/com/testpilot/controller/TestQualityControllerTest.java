package com.testpilot.controller;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.CoverageResult;
import com.testpilot.dto.TestQualityResult;
import com.testpilot.dto.TestQualityStaticStats;
import com.testpilot.service.TestQualityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

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
        return new TestQualityResult("p1", stats, cov);
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
