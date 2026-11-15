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
import com.testpilot.utils.PromptBuilder;
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

        // 7. 计算测试文件保存路径
        Path testFilePath = PromptBuilder.resolveTestFilePath(projectDir, ctx);

        // 8. 保存（原子写入）
        String relativeTestPath = saveTestFile(projectDir, testFilePath, code);

        return new TestGenerationResponse(relativeTestPath, code, "GENERATED");
    }

    /**
     * 原子写入测试文件：先写临时文件，再 move 到目标位置。
     * 目标文件已存在时拒绝覆盖。
     */
    private String saveTestFile(Path projectDir, Path testFilePath, String code) {
        if (Files.exists(testFilePath)) {
            throw new BusinessException(409, "测试文件已存在，拒绝覆盖: "
                    + projectDir.relativize(testFilePath).toString().replace("\\", "/"));
        }

        try {
            Files.createDirectories(testFilePath.getParent());

            // 临时文件写入
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
        } catch (IOException e) {
            log.error("保存测试文件失败: {}", testFilePath, e);
            throw new BusinessException(500, "保存测试文件失败");
        }

        return projectDir.relativize(testFilePath).toString().replace("\\", "/");
    }
}
