package com.testpilot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.common.exception.LLMException;
import com.testpilot.config.LLMProperties;
import com.testpilot.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BailianLLMServiceImpl implements LLMService {

    private static final Logger log = LoggerFactory.getLogger(BailianLLMServiceImpl.class);

    private final RestTemplate restTemplate;
    private final LLMProperties properties;
    private final ObjectMapper objectMapper;

    public BailianLLMServiceImpl(RestTemplate restTemplate, LLMProperties properties,
                                  ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String chat(String prompt) {
        if (!properties.hasApiKey()) {
            throw LLMException.apiKeyNotConfigured();
        }

        String url = properties.getBaseUrl() + "/chat/completions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", properties.getModel());
        requestBody.put("messages", List.of(message));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            log.info("调用 LLM API, model={}, url={}", properties.getModel(), url);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            return parseResponse(response.getBody());
        } catch (HttpStatusCodeException e) {
            HttpStatusCode status = e.getStatusCode();
            int code = status.value();
            String body = e.getResponseBodyAsString();
            log.warn("LLM API 返回错误状态码: {}, body={}", code, truncate(body));
            if (code == 401) {
                throw LLMException.unauthorized();
            } else if (code == 403) {
                throw LLMException.forbidden();
            } else if (code == 404) {
                throw LLMException.notFound();
            } else if (code == 429) {
                throw LLMException.rateLimited();
            } else if (status.is5xxServerError()) {
                throw LLMException.serverError("HTTP " + code);
            } else {
                throw LLMException.serverError("HTTP " + code);
            }
        } catch (ResourceAccessException e) {
            log.error("LLM API 网络异常", e);
            throw LLMException.timeout();
        } catch (RestClientException e) {
            log.error("LLM API 调用异常", e);
            throw LLMException.serverError(e.getMessage());
        }
    }

    private String parseResponse(String body) {
        if (body == null || body.isBlank()) {
            throw LLMException.emptyContent();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw LLMException.parseError("响应缺少 choices 数组");
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw LLMException.emptyContent();
            }
            String text = content.asText();
            if (text.isBlank()) {
                throw LLMException.emptyContent();
            }
            return text;
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 LLM 响应 JSON 失败", e);
            throw LLMException.parseError(e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
