package com.artinus.subscription.external.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Google Generative Language API({@code generateContent}) 응답 본문.
 *
 * <p>실제 응답에는 {@code usageMetadata}, {@code modelVersion}, {@code promptFeedback} 등 부가 필드가 있으나
 * 어댑터가 사용하는 것은 {@code candidates[0].content.parts[0].text}와 {@code finishReason}뿐이다.
 * 향후 Google API 필드 추가에 안전하도록 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 적용.</p>
 *
 * <p>안전 필터에 차단되면 {@code candidates[0].finishReason}이 "SAFETY" 또는 "BLOCKED"로 반환된다.
 * 본 record는 단순 매핑만 담당하고 정책 해석(차단=실패)은 {@link GeminiClient}에서 수행한다.</p>
 */
@Schema(description = "Gemini generateContent 응답 본문")
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiResponse(
        @Schema(description = "응답 후보 배열. 본 어댑터는 첫 번째 후보만 사용")
        List<Candidate> candidates
) {

    /**
     * 단일 후보 응답.
     *
     * @param content      생성된 콘텐츠. parts 안에 텍스트가 들어있다.
     * @param finishReason 종료 사유. "STOP"(정상), "MAX_TOKENS", "SAFETY", "BLOCKED" 등
     */
    @Schema(description = "단일 응답 후보")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
            @Schema(description = "생성된 콘텐츠")
            Content content,
            @Schema(description = "종료 사유 (STOP/MAX_TOKENS/SAFETY/BLOCKED)", example = "STOP")
            String finishReason
    ) {
    }

    /**
     * 생성된 콘텐츠의 래퍼.
     *
     * @param role  응답 역할 (보통 "model")
     * @param parts 텍스트 조각 배열
     */
    @Schema(description = "생성된 콘텐츠")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(
            @Schema(description = "응답 역할", example = "model")
            String role,
            @Schema(description = "텍스트 파트 배열")
            List<Part> parts
    ) {
    }

    /**
     * 단일 텍스트 조각.
     *
     * @param text 생성된 평문 텍스트
     */
    @Schema(description = "생성된 텍스트 파트")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(
            @Schema(description = "생성된 평문 텍스트")
            String text
    ) {
    }
}
