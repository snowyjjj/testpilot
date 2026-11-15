package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 从 Maven 执行结果中提取的结构化失败反馈，用于构造修复 Prompt。
 */
@Getter
@AllArgsConstructor
public class FailureFeedback {

    /** 失败类型：COMPILE 或 TEST */
    private final FailureType failureType;

    /** 编译错误来源（仅 COMPILE 时有意义） */
    private final CompileErrorSource compileErrorSource;

    /** 编译错误摘要列表（每条为一行错误信息，如 file.java:[10,13] cannot find symbol） */
    private final List<String> compileErrors;

    /** 失败用例详情列表 */
    private final List<FailedCaseInfo> failedCases;

    /** 供 LLM 阅读的简短摘要 */
    private final String summary;

    public enum FailureType {
        COMPILE, TEST
    }

    /**
     * 编译错误来源分类。只有 TEST_SOURCE 才允许 LLM 自动修复。
     */
    public enum CompileErrorSource {
        /** 错误来自 TestPilot 生成的测试源码文件 */
        TEST_SOURCE,
        /** 错误来自用户业务源码（src/main/java） */
        MAIN_SOURCE,
        /** 依赖解析失败 */
        DEPENDENCY,
        /** Maven 插件错误 */
        PLUGIN,
        /** 无法确定 */
        UNKNOWN
    }

    public FailureFeedback(FailureType failureType, List<String> compileErrors,
                           List<FailedCaseInfo> failedCases, String summary) {
        this(failureType, CompileErrorSource.UNKNOWN, compileErrors, failedCases, summary);
    }

    @Getter
    @AllArgsConstructor
    public static class FailedCaseInfo {
        private final String className;
        private final String methodName;
        private final String exceptionType;
        private final String message;
        private final String stackTrace;
    }
}
