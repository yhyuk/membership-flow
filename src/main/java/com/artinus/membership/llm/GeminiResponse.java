package com.artinus.membership.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Gemini generateContent 응답. 어댑터는 candidates[0].content.parts[0].text와 finishReason만 사용. */
@Schema(description = "Gemini generateContent 응답 본문")
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiResponse(
        List<Candidate> candidates
) {

    /** finishReason: STOP / MAX_TOKENS / SAFETY / BLOCKED 등. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
            Content content,
            String finishReason
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(
            String role,
            List<Part> parts
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(
            String text
    ) {
    }
}
