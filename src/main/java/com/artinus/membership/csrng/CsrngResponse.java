package com.artinus.membership.csrng;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * csrng 응답 단건. 실제 응답은 배열이므로 {@code List<CsrngResponse>}로 받아 첫 원소를 사용한다.
 * 예: {@code [{ "status": "success", "min": 0, "max": 1, "random": 1 }]}
 */
@Schema(description = "csrng 외부 난수 API 응답 단건")
@JsonIgnoreProperties(ignoreUnknown = true)
public record CsrngResponse(
        @Schema(description = "csrng 자체 처리 상태. \"success\"가 아니면 어댑터가 실패로 분류", example = "success")
        String status,

        @Schema(description = "요청 최소값(회신)", example = "0")
        Integer min,

        @Schema(description = "요청 최대값(회신)", example = "1")
        Integer max,

        @Schema(description = "발생한 난수 (0 또는 1)", example = "1")
        Integer random
) {
}
