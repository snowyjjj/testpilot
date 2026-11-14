package com.testpilot.controller;

import com.testpilot.common.Result;
import com.testpilot.dto.TestGenerationRequest;
import com.testpilot.dto.TestGenerationResponse;
import com.testpilot.service.TestGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class TestGenerationController {

    private final TestGenerationService testGenerationService;

    @PostMapping("/{projectId}/tests/generate")
    public Result<TestGenerationResponse> generateTest(
            @PathVariable String projectId,
            @Valid @RequestBody TestGenerationRequest request) {
        TestGenerationResponse response = testGenerationService.generateTest(projectId, request);
        return Result.success(response);
    }
}
