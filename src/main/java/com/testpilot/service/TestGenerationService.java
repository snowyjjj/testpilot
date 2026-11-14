package com.testpilot.service;

import com.testpilot.dto.TestGenerationRequest;
import com.testpilot.dto.TestGenerationResponse;

public interface TestGenerationService {

    TestGenerationResponse generateTest(String projectId, TestGenerationRequest request);
}
