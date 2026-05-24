package com.artinus.membership.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Gemini generateContent 요청 본문. null 필드는 직렬화 제외. */
@Schema(description = "Gemini generateContent 요청 본문")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiRequest(
        Content systemInstruction,
        List<Content> contents,
        GenerationConfig generationConfig
) {

    /** role + parts. systemInstruction에서는 role 생략. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Content(
            String role,
            List<Part> parts
    ) {
    }

    /** PII 미포함 평문 텍스트. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(
            String text
    ) {
    }

    /** maxOutputTokens=512, temperature=0.2 권장. thinkingConfig는 2.5 모델의 reasoning 토큰 제어용. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationConfig(
            Integer maxOutputTokens,
            Double temperature,
            ThinkingConfig thinkingConfig
    ) {
    }

    /** Gemini 2.5 reasoning 모델 thinking 예산 (0 = 비활성). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ThinkingConfig(
            Integer thinkingBudget
    ) {
    }
}
