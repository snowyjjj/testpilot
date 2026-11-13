package com.testpilot.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LLMChatRequest implements Serializable {

    @NotBlank(message = "prompt 不能为空")
    private String prompt;
}
