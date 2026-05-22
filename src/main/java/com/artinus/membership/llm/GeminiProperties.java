package com.artinus.membership.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * external.llm.gemini.* 프로퍼티.
 * apiKey는 환경변수 GEMINI_API_KEY로 주입. 빈 문자열이면 GeminiClient가 호출 전 fail-fast.
 */
@ConfigurationProperties(prefix = "external.llm.gemini")
public record GeminiProperties(
        String baseUrl,
        String model,
        String apiKey,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxRecentHistories
) {
}
