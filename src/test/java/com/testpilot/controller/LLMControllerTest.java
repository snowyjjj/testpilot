package com.testpilot.controller;

import com.testpilot.common.exception.LLMException;
import com.testpilot.service.LLMService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LLMController.class)
class LLMControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LLMService llmService;

    @Test
    void test_success_returnsReply() throws Exception {
        when(llmService.chat("你好")).thenReturn("你好！我是模型回复。");

        mockMvc.perform(post("/api/llm/test")
                        .contentType("application/json")
                        .content("{\"prompt\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("你好！我是模型回复。"));
    }

    @Test
    void test_emptyPrompt_returns400() throws Exception {
        mockMvc.perform(post("/api/llm/test")
                        .contentType("application/json")
                        .content("{\"prompt\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void test_llmException_propagatesHttpStatus() throws Exception {
        when(llmService.chat(anyString())).thenThrow(LLMException.unauthorized());

        mockMvc.perform(post("/api/llm/test")
                        .contentType("application/json")
                        .content("{\"prompt\":\"test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void test_apiKeyNotConfigured_returns500() throws Exception {
        when(llmService.chat(anyString())).thenThrow(LLMException.apiKeyNotConfigured());

        mockMvc.perform(post("/api/llm/test")
                        .contentType("application/json")
                        .content("{\"prompt\":\"test\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("API Key")));
    }
}
