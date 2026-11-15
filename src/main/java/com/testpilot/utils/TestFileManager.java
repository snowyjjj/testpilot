package com.testpilot.utils;

import com.testpilot.dto.MethodContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 测试文件管理器：定位、读取、备份、安全更新和恢复测试文件。
 * <p>
 * 安全约束：
 * - 只能通过 MethodContext 定位已生成的测试文件，不接受用户传入任意路径。
 * - 更新前保存原始代码副本，异常时恢复。
 * - 使用临时文件 + 原子移动写入。
 * - 路径边界检查确保文件在项目目录内。
 */
public final class TestFileManager {

    private static final Logger log = LoggerFactory.getLogger(TestFileManager.class);

    private TestFileManager() {
    }

    /**
     * 根据 MethodContext 定位已生成的测试文件路径。
     */
    public static Path locateTestFile(Path projectDir, MethodContext ctx) {
        return PromptBuilder.resolveTestFilePath(projectDir, ctx);
    }

    /**
     * 读取测试文件内容。文件不存在时返回 null。
     */
    public static String readTestFile(Path testFilePath) {
        if (!Files.isRegularFile(testFilePath)) {
            return null;
        }
        try {
            return Files.readString(testFilePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取测试文件失败: {}", testFilePath, e);
            return null;
        }
    }

    /**
     * 安全更新测试文件：
     * 1. 校验路径在 projectDir 内。
     * 2. 读取原始代码作为备份。
     * 3. 临时文件写入新代码，原子移动覆盖。
     * 4. 失败时抛出异常（调用方负责恢复）。
     *
     * @return 原始文件内容（备份），用于异常恢复
     */
    public static String updateTestFile(Path projectDir, Path testFilePath, String newCode) throws IOException {
        // 边界检查
        Path normalizedTarget = testFilePath.normalize();
        Path normalizedProject = projectDir.normalize();
        if (!normalizedTarget.startsWith(normalizedProject)) {
            throw new IOException("测试文件路径越界: " + testFilePath);
        }

        // 读取原始内容作为备份
        String original = Files.isRegularFile(testFilePath)
                ? Files.readString(testFilePath, StandardCharsets.UTF_8)
                : null;

        // 临时文件 + 原子移动
        Path parent = testFilePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = Files.createTempFile(parent, ".repair-", ".tmp");
        try {
            Files.writeString(tempFile, newCode, StandardCharsets.UTF_8);
            Files.move(tempFile, testFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(tempFile)) {
                Files.deleteIfExists(tempFile);
            }
        }
        return original;
    }

    /**
     * 恢复测试文件到指定内容（异常回滚时使用）。
     */
    public static void restoreTestFile(Path testFilePath, String originalCode) {
        if (originalCode == null) {
            // 原始不存在则删除
            try {
                Files.deleteIfExists(testFilePath);
            } catch (IOException e) {
                log.warn("恢复测试文件失败（删除）: {}", testFilePath, e);
            }
            return;
        }
        try {
            Path parent = testFilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(testFilePath, originalCode, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("恢复测试文件失败: {}", testFilePath, e);
        }
    }
}
