package com.testpilot.service;

public interface LLMService {

    /**
     * 发送单条 user prompt 并返回模型回复文本。
     */
    String chat(String prompt);
}
