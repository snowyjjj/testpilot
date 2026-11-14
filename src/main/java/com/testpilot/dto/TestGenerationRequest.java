package com.testpilot.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 测试生成请求。
 */
@Data
@NoArgsConstructor
public class TestGenerationRequest {

    @NotBlank(message = "类名不能为空")
    private String className;

    @NotBlank(message = "方法名不能为空")
    private String methodName;

    /** 参数类型列表（顺序敏感），为空表示无参方法 */
    private List<String> paramTypes;
}
