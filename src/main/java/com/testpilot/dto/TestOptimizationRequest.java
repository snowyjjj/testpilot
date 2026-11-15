package com.testpilot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 测试优化请求。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestOptimizationRequest implements Serializable {

    @NotBlank(message = "类名不能为空")
    private String className;

    @NotBlank(message = "方法名不能为空")
    private String methodName;

    /** 参数类型列表，用于精确定位重载方法 */
    private List<String> paramTypes;

    /** 最大自动修复次数，默认 2，最大 2 */
    @Max(value = 2, message = "最大修复次数不能超过 2")
    private Integer maxRetries = 2;
}
