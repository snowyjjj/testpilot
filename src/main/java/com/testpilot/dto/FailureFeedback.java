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

    /** 编译错误摘要列表（每条为一行错误信息，如 file.java:[10,13] cannot find symbol） */
    private final List<String> compileErrors;

    /** 失败用例详情列表 */
    private final List<FailedCaseInfo> failedCases;

    /** 供 LLM 阅读的简短摘要 */
    private final String summary;

    public enum FailureType {
        COMPILE, TEST
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
