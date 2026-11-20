package com.testpilot.service.impl;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.JUnitVersion;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.TestGenerationRequest;
import com.testpilot.dto.TestGenerationResponse;
import com.testpilot.service.LLMService;
import com.testpilot.service.ProjectService;
import com.testpilot.service.TestGenerationService;
import com.testpilot.utils.JUnitDetector;
import com.testpilot.utils.ProjectExecutionLock;
import com.testpilot.utils.PromptBuilder;
import com.testpilot.utils.TestFileManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestGenerationServiceImpl implements TestGenerationService {

    private final ProjectService projectService;
    private final LLMService llmService;
    private final ProjectExecutionLock projectExecutionLock;

    @Override
    public TestGenerationResponse generateTest(String projectId, TestGenerationRequest request) {
        // 1. 定位被测方法
        MethodContext ctx = projectService.getMethodContext(
                projectId, request.getClassName(), request.getMethodName(), request.getParamTypes());

        // 2. 检测 JUnit 版本
        Path projectDir = projectService.getProjectDir(projectId);
        JUnitVersion junitVersion = JUnitDetector.detect(projectDir);
        if (junitVersion == JUnitVersion.UNKNOWN) {
            throw new BusinessException(400, "无法识别项目的 JUnit 版本，请在 pom.xml 中声明 junit 或 org.junit.jupiter 依赖");
        }
        if (junitVersion == JUnitVersion.CONFLICT) {
            throw new BusinessException(400, "项目同时声明了 JUnit 4 和 JUnit 5 依赖，无法确定测试框架版本，请移除其中一个");
        }
        log.info("检测到项目 JUnit 版本: {}", junitVersion.getDisplayName());

        // 3. 构建 Prompt
        String prompt = PromptBuilder.buildTestPrompt(ctx, junitVersion);

        // 4. 调用 LLM
        String response = llmService.chat(prompt);

        // 5. 提取 Java 代码
        String code = PromptBuilder.extractJavaCode(response);

        // 6. 校验（含版本相关 import 检查）
        String validationError = PromptBuilder.validateGeneratedCode(code, ctx.getPackageName(), junitVersion);
        if (validationError != null) {
            throw new BusinessException(500, "生成的测试代码校验失败: " + validationError);
        }

        // 7. 计算测试文件保存路径（纯计算，不持锁）
        Path testFilePath = PromptBuilder.resolveTestFilePath(projectDir, ctx);

        // 8. 写入前获取项目锁：与测试执行、覆盖率、测试优化、质量评估互斥，
        //    避免并发修改 src/test/java。锁不包裹上方耗时的 LLM 调用，仅保护真正的文件写入。
        if (!projectExecutionLock.tryLock(projectId)) {
            throw new BusinessException(409, "该项目正在执行其他操作，请稍后重试");
        }
        try {
            String relativeTestPath = saveTestFile(projectDir, testFilePath, code);
            return new TestGenerationResponse(relativeTestPath, code, "GENERATED");
        } finally {
            projectExecutionLock.unlock(projectId);
        }
    }

    /**
     * 原子写入测试文件：先写临时文件，再 move 到目标位置。
     * 目标文件已存在时拒绝覆盖。
     * <p>
     * <b>调用约束</b>：必须在已获取 {@link ProjectExecutionLock}（同一 projectId）的前提下调用，
     * 所有写入路径都经过锁保护，不存在绕过锁的入口。
     */
    private String saveTestFile(Path projectDir, Path testFilePath, String code) {
        try {
            // 写入前边界校验：复用 TestFileManager 的权威路径校验（双端归一化 + 符号链接链检测
            // + 真实路径二次确认），防止测试文件被写到项目目录之外。
            // resolveTestFilePath 已做 normalize 前缀快速校验，此处为写入边界的纵深防御，
            // 专门覆盖符号链接逃逸与校验后被替换（TOCTOU）的场景。
            TestFileManager.assertPathWithinProject(projectDir, testFilePath);

            if (Files.exists(testFilePath)) {
                throw new BusinessException(409, "测试文件已存在，拒绝覆盖: "
                        + projectDir.relativize(testFilePath).toString().replace("\\", "/"));
            }

            Files.createDirectories(testFilePath.getParent());

            // 临时文件在已通过边界校验的父目录下创建，不会落到项目目录之外
            Path tempFile = Files.createTempFile(testFilePath.getParent(), ".test-", ".tmp");
            try {
                Files.writeString(tempFile, code);
                // 原子移动
                Files.move(tempFile, testFilePath, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                // 如果 move 失败，清理临时文件
                if (Files.exists(tempFile)) {
                    Files.deleteIfExists(tempFile);
                }
            }

            // 移动后再次校验：防止校验与移动之间目标路径被替换为符号链接（TOCTOU）
            TestFileManager.assertPathWithinProject(projectDir, testFilePath);
        } catch (IOException e) {
            log.error("保存测试文件失败: {}", testFilePath, e);
            throw new BusinessException(500, "保存测试文件失败");
        }

        return projectDir.relativize(testFilePath).toString().replace("\\", "/");
    }
}
