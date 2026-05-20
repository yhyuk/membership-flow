package com.artinus.subscription.external.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Google Generative Language API({@code v1beta/models/{model}:generateContent}) 요청 본문.
 *
 * <p>스펙: <a href="https://ai.google.dev/api/rest/v1beta/models/generateContent">Gemini REST API</a>.
 * 본 record는 SDK를 도입하지 않고 RestClient 패턴을 일관시키기 위해 직접 매핑한다.</p>
 *
 * <p>구성 요소:
 * <ul>
 *   <li>{@code systemInstruction} — 시스템 메시지(역할/규칙). 1개의 {@link Content}.</li>
 *   <li>{@code contents} — 대화 턴. 본 어댑터는 user 1턴만 사용한다.</li>
 *   <li>{@code generationConfig} — 출력 길이/온도 제어.</li>
 * </ul>
 * null 필드는 직렬화에서 제외하여 외부 API에 불필요한 키를 전송하지 않는다.</p>
 */
@Schema(description = "Gemini generateContent 요청 본문")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiRequest(

        @Schema(description = "시스템 지시문 (역할/규칙). 1개의 Content로 표현")
        Content systemInstruction,

        @Schema(description = "대화 턴 배열. 본 어댑터는 user 1턴만 사용")
        List<Content> contents,

        @Schema(description = "출력 길이/온도 제어")
        GenerationConfig generationConfig
) {

    /**
     * 단일 대화 턴 또는 시스템 지시문의 컨테이너.
     *
     * @param role  대화 역할 ("user" 또는 null — systemInstruction에서는 생략 가능)
     * @param parts 텍스트 조각 배열. 본 어댑터는 단일 텍스트 파트만 사용
     */
    @Schema(description = "Gemini Content (단일 대화 턴 또는 시스템 지시문)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Content(
            @Schema(description = "대화 역할 (user). 시스템 지시문에서는 생략", example = "user")
            String role,
            @Schema(description = "텍스트 파트 배열")
            List<Part> parts
    ) {
    }

    /**
     * 단일 텍스트 조각.
     *
     * @param text 평문 텍스트 (PII 미포함 — handoff §3.6)
     */
    @Schema(description = "단일 텍스트 파트")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(
            @Schema(description = "평문 텍스트. 개인정보(전화번호 등) 미포함")
            String text
    ) {
    }

    /**
     * 생성 파라미터.
     *
     * @param maxOutputTokens 응답 최대 토큰 수 (요약 200자 가정 → 300 토큰)
     * @param temperature     0~1, 낮을수록 결정적. 사실적 요약은 0.2 권장
     */
    @Schema(description = "Gemini generationConfig")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationConfig(
            @Schema(description = "응답 최대 토큰 수", example = "300")
            Integer maxOutputTokens,
            @Schema(description = "0~1 범위 sampling 온도. 낮을수록 결정적", example = "0.2")
            Double temperature
    ) {
    }
}
