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
 * - 路径边界检查（真实路径 + 符号链接检测）确保文件在项目目录内。
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
     * 1. 校验路径在 projectDir 内（真实路径 + 符号链接检测）。
     * 2. 读取原始代码作为备份。
     * 3. 临时文件写入新代码，原子移动覆盖。
     * 4. 失败时抛出异常（调用方负责恢复）。
     *
     * @return 原始文件内容（备份），用于异常恢复
     */
    public static String updateTestFile(Path projectDir, Path testFilePath, String newCode) throws IOException {
        // 1. 真实路径边界校验 + 符号链接检测
        assertPathWithinProject(projectDir, testFilePath);

        // 2. 读取原始内容作为备份
        String original = Files.isRegularFile(testFilePath)
                ? Files.readString(testFilePath, StandardCharsets.UTF_8)
                : null;

        // 3. 临时文件 + 原子移动
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

        // 4. 移动后再次校验目标文件真实路径仍在项目内（防止移动过程中被替换为符号链接）
        assertPathWithinProject(projectDir, testFilePath);
        return original;
    }

    /**
     * 恢复测试文件到指定内容（异常回滚时使用）。
     * 恢复前也做路径边界校验。
     */
    public static void restoreTestFile(Path projectDir, Path testFilePath, String originalCode) {
        if (originalCode == null) {
            // 原始不存在则删除
            try {
                assertPathWithinProject(projectDir, testFilePath);
                Files.deleteIfExists(testFilePath);
            } catch (IOException e) {
                log.warn("恢复测试文件失败（删除）: {}", testFilePath, e);
            }
            return;
        }
        try {
            assertPathWithinProject(projectDir, testFilePath);
            Path parent = testFilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(testFilePath, originalCode, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("恢复测试文件失败: {}", testFilePath, e);
        }
    }

    /**
     * 校验目标路径在项目目录内，并检测符号链接风险。
     * <p>
     * 校验逻辑：
     * 1. projectDir 解析为真实绝对路径。
     * 2. target normalize 后必须 startsWith 真实 projectDir。
     * 3. 检查 target 本身及其每一级父目录（直到 projectDir）是否为符号链接。
     * 4. 若 target 已存在，用 toRealPath 再次确认真实路径在项目内。
     */
    public static void assertPathWithinProject(Path projectDir, Path target) throws IOException {
        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();

        // 1. 规范化路径前缀检查（避免 toRealPath 与 normalize 字符串差异导致误判）
        if (!normalizedTarget.startsWith(normalizedProject)) {
            throw new IOException("测试文件路径越界: " + target);
        }

        // 2. 检查 target 本身是否为符号链接
        if (Files.isSymbolicLink(normalizedTarget)) {
            throw new IOException("测试文件路径包含符号链接: " + target);
        }

        // 3. 检查 target 的每一级父目录（从 target 的父目录开始，直到 projectDir）
        Path parent = normalizedTarget.getParent();
        while (parent != null && !parent.equals(normalizedProject) && parent.startsWith(normalizedProject)) {
            if (Files.isSymbolicLink(parent)) {
                throw new IOException("测试文件父目录包含符号链接: " + parent);
            }
            parent = parent.getParent();
        }

        // 4. 若 target 已存在，解析真实路径再次确认真实路径在项目内
        //    （符号链接若指向项目外，toRealPath 后将不再以 realProject 开头）
        if (Files.exists(normalizedTarget)) {
            Path realTarget = normalizedTarget.toRealPath();
            Path realProject = normalizedProject.toRealPath();
            if (!realTarget.startsWith(realProject)) {
                throw new IOException("测试文件真实路径越界: " + realTarget);
            }
        }
    }
}
