package com.testpilot.service.impl;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.FailureFeedback;
import com.testpilot.dto.FailureFeedback.CompileErrorSource;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 正在优化的项目集合，防止同一项目并发修改测试文件 */
    private final Set<String> optimizingProjects = ConcurrentHashMap.newKeySet();

    @Override
    public TestOptimizationResult optimize(String projectId, TestOptimizationRequest request) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(400, "项目 ID 不能为空");
        }
        if (request == null) {
            throw new BusinessException(400, "请求体不能为空");
        }

        // 并发控制：同一项目不允许同时优化
        if (!optimizingProjects.add(projectId)) {
            throw new BusinessException(409, "该项目正在执行测试优化，请稍后重试");
        }

        try {
            return doOptimize(projectId, request);
        } finally {
            optimizingProjects.remove(projectId);
        }
    }

    private TestOptimizationResult doOptimize(String projectId, TestOptimizationRequest request) {
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
        String testFileName = testFilePath.getFileName().toString();

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

        // 初始编译错误需判断来源
        if ("COMPILE_ERROR".equals(initialResult.getStatus())) {
            FailureFeedback initialFeedback = ExecutionFeedbackExtractor.extract(initialResult, surefireDir, testFileName);
            if (initialFeedback != null && initialFeedback.getCompileErrorSource() != CompileErrorSource.TEST_SOURCE) {
                log.info("初始编译错误来源为 {}，非测试源码错误，不进入修复流程",
                        initialFeedback.getCompileErrorSource());
                return new TestOptimizationResult(projectId, initialResult, 0, false, initialResult, records);
            }
        }

        // 6. 修复循环
        TestExecutionResult lastResult = initialResult;
        int retryCount = 0;
        boolean success = false;

        for (int retry = 1; retry <= maxRetries; retry++) {
            retryCount = retry;
            log.info("开始第 {} 轮修复, projectId={}", retry, projectId);

            // 6a. 提取失败反馈
            FailureFeedback feedback = ExecutionFeedbackExtractor.extract(lastResult, surefireDir, testFileName);

            // 编译错误来源校验：只有 TEST_SOURCE 才允许 LLM 修复
            if (feedback != null && feedback.getFailureType() == FailureFeedback.FailureType.COMPILE
                    && feedback.getCompileErrorSource() != CompileErrorSource.TEST_SOURCE) {
                log.info("第 {} 轮编译错误来源为 {}，停止修复", retry, feedback.getCompileErrorSource());
                records.add(new RepairRecord(retry,
                        feedback.getFailureType().name(),
                        feedback.getSummary() + "（非测试源码错误，不允许自动修复）",
                        false, lastResult));
                break;
            }

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

            // 6d. 提取 + 增强校验代码
            String repairedCode = PromptBuilder.extractJavaCode(response);
            String validationError = PromptBuilder.validateRepairedCode(
                    currentTestCode, repairedCode, ctx.getClassName(), ctx.getPackageName(), junitVersion);
            if (validationError != null) {
                log.warn("修复代码校验失败: {}", validationError);
                records.add(new RepairRecord(retry,
                        feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                        "代码校验失败: " + validationError,
                        false, lastResult));
                // 校验失败不更新文件，继续下一轮或结束
                continue;
            }

            // 6e. 备份并更新测试文件
            String backupCode = currentTestCode;
            try {
                TestFileManager.updateTestFile(projectDir, testFilePath, repairedCode);
                currentTestCode = repairedCode;
                log.info("第 {} 轮修复代码已保存", retry);
            } catch (Exception e) {
                log.error("保存修复代码失败", e);
                boolean restored = TestFileManager.restoreTestFile(projectDir, testFilePath, backupCode);
                records.add(new RepairRecord(retry,
                        feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                        "保存修复代码失败: " + e.getMessage()
                                + (restored ? "，已恢复原始代码" : "，且恢复原始代码失败"),
                        false, lastResult));
                break;
            }

            // 6f. 重新执行
            TestExecutionResult retryResult;
            try {
                retryResult = testExecutionService.executeTests(projectId);
            } catch (Exception e) {
                log.error("修复后重新执行失败", e);
                boolean restored = TestFileManager.restoreTestFile(projectDir, testFilePath, backupCode);
                records.add(new RepairRecord(retry,
                        feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                        "重新执行失败: " + e.getMessage()
                                + (restored ? "，已恢复原始代码" : "，且恢复原始代码失败"),
                        restored, lastResult));
                break;
            }

            // 6g. 根据重新执行结果决定下一步
            String retryStatus = retryResult.getStatus();
            if ("PASSED".equals(retryStatus)) {
                success = true;
                records.add(new RepairRecord(retry,
                        feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                        feedback != null ? feedback.getSummary() : "",
                        true, retryResult));
                log.info("第 {} 轮修复成功", retry);
                break;
            }

            if ("COMPILE_ERROR".equals(retryStatus) || "FAILED".equals(retryStatus)) {
                // 仍为可修复状态，检查编译错误来源
                if ("COMPILE_ERROR".equals(retryStatus)) {
                    FailureFeedback retryFeedback = ExecutionFeedbackExtractor.extract(retryResult, surefireDir, testFileName);
                    if (retryFeedback != null && retryFeedback.getCompileErrorSource() != CompileErrorSource.TEST_SOURCE) {
                        log.info("第 {} 轮修复后编译错误来源为 {}，停止修复并恢复测试代码", retry, retryFeedback.getCompileErrorSource());
                        // 恢复本轮修复前的测试代码
                        boolean restored = TestFileManager.restoreTestFile(projectDir, testFilePath, backupCode);
                        if (restored) {
                            currentTestCode = backupCode;
                        }
                        String summary = retryFeedback.getSummary() + "（非测试源码错误，不允许继续修复）"
                                + (restored ? "，已恢复测试代码" : "，但恢复测试代码失败");
                        records.add(new RepairRecord(retry,
                                feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                                summary,
                                restored, retryResult));
                        lastResult = retryResult;
                        break;
                    }
                }
                // 记录本轮结果，继续下一轮
                records.add(new RepairRecord(retry,
                        feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                        feedback != null ? feedback.getSummary() : "",
                        true, retryResult));
                lastResult = retryResult;
                continue;
            }

            // TIMEOUT / MAVEN_ERROR / NO_REPORTS：环境问题，立即停止并恢复本轮修改前的代码
            log.info("第 {} 轮修复后执行状态为 {}，停止修复并恢复测试代码", retry, retryStatus);
            boolean restored = TestFileManager.restoreTestFile(projectDir, testFilePath, backupCode);
            if (restored) {
                currentTestCode = backupCode;
            }
            String summary = "执行状态为 " + retryStatus + "，停止修复"
                    + (restored ? "，已恢复测试代码" : "，但恢复测试代码失败");
            records.add(new RepairRecord(retry,
                    feedback != null ? feedback.getFailureType().name() : "UNKNOWN",
                    summary,
                    restored, retryResult));
            lastResult = retryResult;
            break;
        }

        return new TestOptimizationResult(projectId, initialResult, retryCount, success, lastResult, records);
    }
}
