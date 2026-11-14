package com.testpilot.service.impl;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.TestGenerationRequest;
import com.testpilot.dto.TestGenerationResponse;
import com.testpilot.service.LLMService;
import com.testpilot.service.ProjectService;
import com.testpilot.service.TestGenerationService;
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

        // 2. 构建 Prompt
        String prompt = PromptBuilder.buildTestPrompt(ctx);

        // 3. 调用 LLM
        String response = llmService.chat(prompt);

        // 4. 提取 Java 代码
        String code = PromptBuilder.extractJavaCode(response);

        // 5. 校验
        String validationError = PromptBuilder.validateGeneratedCode(code, ctx.getPackageName());
        if (validationError != null) {
            throw new BusinessException(500, "生成的测试代码校验失败: " + validationError);
        }

        // 6. 计算测试文件保存路径
        Path projectDir = projectService.getProjectDir(projectId);
        Path testFilePath = computeTestFilePath(projectDir, ctx);

        // 7. 保存（原子写入）
        String relativeTestPath = saveTestFile(projectDir, testFilePath, code);

        return new TestGenerationResponse(relativeTestPath, code, "GENERATED");
    }

    /**
     * 根据被测源文件的相对路径推导测试文件路径。
     * 例如：src/main/java/com/example/Calculator.java → src/test/java/com/example/CalculatorTest.java
     * 兼容 ZIP 内存在单层子目录的情况，如 myapp/src/main/java/... → myapp/src/test/java/...
     */
    private Path computeTestFilePath(Path projectDir, MethodContext ctx) {
        String sourceRelative = ctx.getSourceFilePath();
        // 以正斜杠分隔处理
        String normalized = sourceRelative.replace("\\", "/");

        // 将第一个 src/main/java 替换为 src/test/java
        int markerIdx = normalized.indexOf("src/main/java");
        String testRelative;
        if (markerIdx >= 0) {
            testRelative = normalized.substring(0, markerIdx)
                    + "src/test/java"
                    + normalized.substring(markerIdx + "src/main/java".length());
        } else {
            // 非标准结构，尝试在同级创建 test 目录
            int lastSlash = normalized.lastIndexOf('/');
            String dir = lastSlash >= 0 ? normalized.substring(0, lastSlash) : "";
            String file = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
            testRelative = (dir.isEmpty() ? "" : dir + "/") + file;
        }

        // 替换类名 -> 类名Test.java
        String className = ctx.getClassName();
        testRelative = testRelative.replace(className + ".java", className + "Test.java");

        Path resolved = projectDir.resolve(testRelative).normalize();
        // 路径边界检查：确保目标路径在 projectDir 内
        if (!resolved.startsWith(projectDir)) {
            throw new BusinessException(500, "测试文件路径越界: " + testRelative);
        }
        return resolved;
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
