package com.testpilot.service.impl;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.FailureFeedback;
import com.testpilot.dto.JUnitVersion;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.RepairRecord;
import com.testpilot.dto.TestExecutionResult;
import com.testpilot.dto.TestOptimizationRequest;
import com.testpilot.dto.TestOptimizationResult;
import com.testpilot.service.LLMService;
import com.testpilot.service.ProjectService;
import com.testpilot.service.TestExecutionService;
import com.testpilot.service.TestOptimizationService;
import com.testpilot.utils.ExecutionFeedbackExtractor;
import com.testpilot.utils.JUnitDetector;
import com.testpilot.utils.PromptBuilder;
import com.testpilot.utils.TestFileManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试优化服务实现：执行 → 失败反馈 → LLM 修复 → 重新执行 的有限轮次闭环。
 * <p>
 * 安全说明：本服务调用 LLM 修复测试代码并在宿主机重新执行 Maven。
 * 仅适用于本地毕业设计 Demo，运行不可信项目需容器隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestOptimizationServiceImpl implements TestOptimizationService {

    private static final int DEFAULT_MAX_RETRIES = 2;

    private final ProjectService projectService;
    private final TestExecutionService testExecutionService;
    private final LLMService llmService;

    @Override
    public TestOptimizationResult optimize(String projectId, TestOptimizationRequest request) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(400, "项目 ID 不能为空");
        }
        if (request == null) {
            throw new BusinessException(400, "请求体不能为空");
        }

        int maxRetries = request.getMaxRetries() != null ? request.getMaxRetries() : DEFAULT_MAX_RETRIES;
        if (maxRetries < 0) {
            maxRetries = 0;
        }
        if (maxRetries > 2) {
            maxRetries = 2;
        }

        // 1. 定位被测方法上下文
        MethodContext ctx = projectService.getMethodContext(
                projectId, request.getClassName(), request.getMethodName(), request.getParamTypes());

        // 2. 检测 JUnit 版本
        Path projectDir = projectService.getProjectDir(projectId);
        JUnitVersion junitVersion = JUnitDetector.detect(projectDir);
        if (junitVersion == JUnitVersion.UNKNOWN || junitVersion == JUnitVersion.CONFLICT) {
            throw new BusinessException(400, "无法识别项目的 JUnit 版本，无法进行测试优化");
        }

        // 3. 定位已生成的测试文件
        Path testFilePath = TestFileManager.locateTestFile(projectDir, ctx);
        String currentTestCode = TestFileManager.readTestFile(testFilePath);
        if (currentTestCode == null) {
            throw new BusinessException(404, "未找到已生成的测试文件，请先生成测试");
        }

        // 4. 获取 Maven 项目根目录（用于定位 surefire 报告）
        Path mavenDir = projectService.getMavenProjectDir(projectId);
        Path surefireDir = mavenDir.resolve("target").resolve("surefire-reports");

        // 5. 初始执行
        TestExecutionResult initialResult = testExecutionService.executeTests(projectId);
        List<RepairRecord> records = new ArrayList<>();

        if ("PASSED".equals(initialResult.getStatus())) {
            return new TestOptimizationResult(projectId, initialResult, 0, true, initialResult, records);
        }

        // MAVEN_ERROR / TIMEOUT / NO_REPORTS：环境问题或无测试，不调用 LLM
        if (!"COMPILE_ERROR".equals(initialResult.getStatus())
                && !"FAILED".equals(initialResult.getStatus())) {
            log.info("初始执行状态为 {}，不进入修复流程", initialResult.getStatus());
            return new TestOptimizationResult(projectId, initialResult, 0, false, initialResult, records);
        }

        // 6. 修复循环
        TestExecutionResult lastResult = initialResult;
        int retryCount = 0;
        boolean success = false;

        for (int retry = 1; retry <= maxRetries; retry++) {
            retryCount = retry;
            log.info("开始第 {} 轮修复, projectId={}", retry, projectId);

            // 6a. 提取失败反馈
            FailureFeedback feedback = ExecutionFeedbackExtractor.extract(lastResult, surefireDir);

            // 6b. 构建修复 Prompt
            String prompt = PromptBuilder.buildRepairPrompt(ctx, junitVersion, currentTestCode, feedback, retry);

            // 6c. 调用 LLM
            String response;
            try {
                response = llmService.chat(prompt);
            } catch (Exception e) {
                log.error("调用 LLM 修复失败", e);
                records.add(new RepairRecord(retry,
                        feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                        feedback != null ? feedback.getSummary() : "LLM 调用失败",
                        false, lastResult));
                break;
            }

            // 6d. 提取 + 校验代码
            String repairedCode = PromptBuilder.extractJavaCode(response);
            String validationError = PromptBuilder.validateGeneratedCode(
                    repairedCode, ctx.getPackageName(), junitVersion);
            if (validationError != null) {
                log.warn("修复代码校验失败: {}", validationError);
                records.add(new RepairRecord(retry,
                        feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                        feedback != null ? feedback.getSummary() : "代码校验失败: " + validationError,
                        false, lastResult));
                // 校验失败不更新文件，继续下一轮或结束
                continue;
            }

            // 6e. 备份并更新测试文件
            String backupCode = currentTestCode;
            try {
                String original = TestFileManager.updateTestFile(projectDir, testFilePath, repairedCode);
                // 更新成功后，currentTestCode 指向新代码
                currentTestCode = repairedCode;
                log.info("第 {} 轮修复代码已保存", retry);
            } catch (Exception e) {
                log.error("保存修复代码失败", e);
                // 恢复原始代码
                TestFileManager.restoreTestFile(testFilePath, backupCode);
                records.add(new RepairRecord(retry,
                        feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                        "保存修复代码失败: " + e.getMessage(),
                        false, lastResult));
                break;
            }

            // 6f. 重新执行
            TestExecutionResult retryResult;
            try {
                retryResult = testExecutionService.executeTests(projectId);
            } catch (Exception e) {
                log.error("修复后重新执行失败", e);
                // 恢复原始代码
                TestFileManager.restoreTestFile(testFilePath, backupCode);
                records.add(new RepairRecord(retry,
                        feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                        "重新执行失败: " + e.getMessage(),
                        true, lastResult));
                break;
            }

            lastResult = retryResult;
            boolean repaired = "PASSED".equals(retryResult.getStatus());
            records.add(new RepairRecord(retry,
                    feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                    feedback != null ? feedback.getSummary() : "",
                    true, retryResult));

            if (repaired) {
                success = true;
                log.info("第 {} 轮修复成功", retry);
                break;
            }

            // 仍失败，继续下一轮
        }

        return new TestOptimizationResult(projectId, initialResult, retryCount, success, lastResult, records);
    }
}
