package com.testpilot.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.LLMException;
import com.testpilot.config.LLMProperties;
import com.testpilot.service.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BailianLLMServiceImplTest {

    private static final String FULL_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    private RestTemplate restTemplate;
    private LLMProperties properties;
    private MockRestServiceServer server;
    private LLMService llmService;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        properties = new LLMProperties();
        properties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.setModel("qwen-plus");
        properties.setApiKey("test-api-key");
        llmService = new BailianLLMServiceImpl(restTemplate, properties, new ObjectMapper());
    }

    @Test
    void chat_success_returnsContent() {
        server.expect(requestTo(FULL_URL))
                .andExpect(header("Authorization", "Bearer test-api-key"))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(jsonPath("$.model").value("qwen-plus"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("你好"))
                .andRespond(withSuccess(
                        """
                        {"choices":[{"message":{"content":"你好！我是 qwen-plus。"}}]}
                        """, MediaType.APPLICATION_JSON));

        String result = llmService.chat("你好");
        assertEquals("你好！我是 qwen-plus。", result);
        server.verify();
    }

    @Test
    void chat_apiKeyNotConfigured_throws() {
        properties.setApiKey("");
        LLMException ex = assertThrows(LLMException.class, () -> llmService.chat("test"));
        assertEquals(500, ex.getCode());
        assertTrue(ex.getMessage().contains("API Key 未配置"));
    }

    @Test
    void chat_401Unauthorized_throwsLLMException() {
        server.expect(requestTo(FULL_URL))
                .andRespond(withUnauthorizedRequest().body("{\"error\":{\"message\":\"invalid key\"}}"));

        LLMException ex = assertThrows(LLMException.class, () -> llmService.chat("test"));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void chat_403Forbidden_throwsLLMException() {
        server.expect(requestTo(FULL_URL))
                .andRespond(withForbiddenRequest());

        LLMException ex = assertThrows(LLMException.class, () -> llmService.chat("test"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void chat_404NotFound_throwsLLMException() {
        server.expect(requestTo(FULL_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        LLMException ex = assertThrows(LLMException.class, () -> llmService.chat("test"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void chat_429RateLimited_throwsLLMException() {
        server.expect(requestTo(FULL_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        LLMException ex = assertThrows(LLMException.class, () -> llmService.chat("test"));
        assertEquals(429, ex.getCode());
    }

    @Test
    void chat_5xx_throwsServerError() {
        server.expect(requestTo(FULL_URL))
                .andRespond(withServerError());

        LLMException ex = assertThrows(LLMException.class, () -> llmService.chat("test"));
        assertEquals(502, ex.getCode());
    }

    @Test
    void chat_emptyResponseBody_throws() {
        server.expect(requestTo(FULL_URL))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        LLMException ex = assertThrows(LLMException.class, () -> llmService.chat("test"));
        assertEquals(502, ex.getCode());
        assertTrue(ex.getMessage().contains("返回内容为空"));
    }

    @Test
    void chat_responseMissingChoices_throws() {
        server.expect(requestTo(FULL_URL))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        LLMException ex = assertThrows(LLMException.class, () -> llmService.chat("test"));
        assertEquals(502, ex.getCode());
    }

    @Test
    void chat_contentNull_throws() {
        server.expect(requestTo(FULL_URL))
                .andRespond(withSuccess(
                        """
                        {"choices":[{"message":{"content":null}}]}
                        """, MediaType.APPLICATION_JSON));

        LLMException ex = assertThrows(LLMException.class, () -> llmService.chat("test"));
        assertTrue(ex.getMessage().contains("返回内容为空"));
    }
}
