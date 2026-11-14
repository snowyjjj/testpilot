package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 被测方法的完整上下文，用于构建 Prompt 和计算测试文件保存路径。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MethodContext implements Serializable {

    private String packageName;

    private String className;

    /** 被测类所在源文件相对于解压根目录的路径，如 src/main/java/com/example/Calculator.java */
    private String sourceFilePath;

    /** 被测类的完整源码（含 import、字段、构造方法、所有方法） */
    private String classSource;

    /** 被测方法的详细信息 */
    private MethodInfo methodInfo;
}
