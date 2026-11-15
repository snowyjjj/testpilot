package com.testpilot.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestFileManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void assertPathWithinProject_normalPath_ok() throws IOException {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        Path testFile = projectDir.resolve("src/test/java/com/example/CalculatorTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class CalculatorTest {}");

        assertDoesNotThrow(() -> TestFileManager.assertPathWithinProject(projectDir, testFile));
    }

    @Test
    void assertPathWithinProject_pathEscapes_rejected() throws IOException {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        Path escapeTarget = tempDir.resolve("outside").resolve("Evil.java");

        IOException ex = assertThrows(IOException.class,
                () -> TestFileManager.assertPathWithinProject(projectDir, escapeTarget));
        assertTrue(ex.getMessage().contains("越界"));
    }

    @Test
    void updateTestFile_writesContent() throws IOException {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        Path testFile = projectDir.resolve("src/test/java/com/example/CalculatorTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "old");

        String original = TestFileManager.updateTestFile(projectDir, testFile, "new code");

        assertEquals("old", original);
        assertEquals("new code", Files.readString(testFile));
    }

    @Test
    void restoreTestFile_rewritesContent() throws IOException {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        Path testFile = projectDir.resolve("src/test/java/com/example/CalculatorTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "modified");

        TestFileManager.restoreTestFile(projectDir, testFile, "original");

        assertEquals("original", Files.readString(testFile));
    }

    /**
     * 符号链接路径安全测试：仅在支持符号链接创建的操作系统上运行。
     * Windows 需要管理员权限或开发者模式才能创建符号链接，
     * 若创建失败则用 Assumptions 跳过。
     */
    @Test
    void assertPathWithinProject_symlinkEscape_rejected() throws IOException {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        Path realTarget = tempDir.resolve("outside").resolve("Evil.java");
        Files.createDirectories(realTarget.getParent());
        Files.writeString(realTarget, "evil");

        // 在项目目录内创建一个指向项目外的符号链接
        Path symlink = projectDir.resolve("link.java");
        boolean symlinkSupported;
        try {
            Files.createSymbolicLink(symlink, realTarget);
            symlinkSupported = Files.isSymbolicLink(symlink);
        } catch (IOException | UnsupportedOperationException e) {
            symlinkSupported = false;
        }
        // 部分 Windows 环境下 createSymbolicLink 成功但 isSymbolicLink 返回 false，此时跳过
        org.junit.jupiter.api.Assumptions.assumeTrue(symlinkSupported,
                "当前环境无法检测符号链接，跳过符号链接安全测试");

        IOException ex = assertThrows(IOException.class,
                () -> TestFileManager.assertPathWithinProject(projectDir, symlink));
        assertTrue(ex.getMessage().contains("符号链接"));
    }

    @Test
    void assertPathWithinProject_parentSymlink_rejected() throws IOException {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        Path realOutsideDir = tempDir.resolve("outsideDir");
        Files.createDirectories(realOutsideDir);

        // 在项目内创建指向项目外目录的符号链接目录
        Path symlinkDir = projectDir.resolve("linkdir");
        boolean symlinkSupported;
        try {
            Files.createSymbolicLink(symlinkDir, realOutsideDir);
            symlinkSupported = Files.isSymbolicLink(symlinkDir);
        } catch (IOException | UnsupportedOperationException e) {
            symlinkSupported = false;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(symlinkSupported,
                "当前环境无法检测符号链接，跳过符号链接安全测试");

        Path targetInSymlinkDir = symlinkDir.resolve("Evil.java");

        IOException ex = assertThrows(IOException.class,
                () -> TestFileManager.assertPathWithinProject(projectDir, targetInSymlinkDir));
        assertTrue(ex.getMessage().contains("符号链接"));
    }
}
