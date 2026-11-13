package com.testpilot.common.exception;

import lombok.Getter;

/**
 * 大模型调用异常。code 字段用于映射 HTTP 状态码，message 不包含 API Key 等敏感信息。
 */
@Getter
public class LLMException extends RuntimeException {

    private final int code;

    public LLMException(int code, String message) {
        super(message);
        this.code = code;
    }

    public LLMException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    // 细分错误码，直接对应 HTTP 状态码，方便 GlobalExceptionHandler 映射
    public static LLMException apiKeyNotConfigured() {
        return new LLMException(500, "LLM API Key 未配置，请设置环境变量 DASHSCOPE_API_KEY");
    }

    public static LLMException unauthorized() {
        return new LLMException(401, "LLM 鉴权失败 (HTTP 401)，请检查 API Key 和地域");
    }

    public static LLMException forbidden() {
        return new LLMException(403, "LLM 权限不足 (HTTP 403)，请检查模型访问权限");
    }

    public static LLMException notFound() {
        return new LLMException(404, "LLM 接口或模型不存在 (HTTP 404)，请检查 base-url 和 model 配置");
    }

    public static LLMException rateLimited() {
        return new LLMException(429, "LLM 请求频率或额度超限 (HTTP 429)");
    }

    public static LLMException serverError(String msg) {
        return new LLMException(502, "LLM 服务端异常: " + msg);
    }

    public static LLMException timeout() {
        return new LLMException(504, "LLM 请求超时");
    }

    public static LLMException parseError(String msg) {
        return new LLMException(502, "LLM 响应解析失败: " + msg);
    }

    public static LLMException emptyContent() {
        return new LLMException(502, "LLM 返回内容为空");
    }
}
