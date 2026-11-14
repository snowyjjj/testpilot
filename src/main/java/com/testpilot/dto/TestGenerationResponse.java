package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 测试生成响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestGenerationResponse implements Serializable {

    /** 测试文件相对于项目解压根目录的路径 */
    private String testFilePath;

    /** 生成的测试代码 */
    private String generatedCode;

    /** 状态：GENERATED */
    private String status;
}
