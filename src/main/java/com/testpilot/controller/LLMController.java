package com.testpilot.controller;

import com.testpilot.common.Result;
import com.testpilot.dto.LLMChatRequest;
import com.testpilot.service.LLMService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm")
public class LLMController {

    private final LLMService llmService;

    public LLMController(LLMService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/test")
    public Result<String> test(@Valid @RequestBody LLMChatRequest request) {
        String reply = llmService.chat(request.getPrompt());
        return Result.success(reply);
    }
}
