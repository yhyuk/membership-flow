package com.artinus.membership.csrng;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * csrng 외부 난수 API 응답 단건.
 *
 * <p>ASSIGNMENT.md(§외부 API) 응답 예시:
 * <pre>[{ "status": "success", "min": 0, "max": 1, "random": 1 }]</pre>
 *
 * <p>실제 응답은 배열이므로 {@code List<CsrngResponse>}로 역직렬화한 뒤
 * 첫 번째 원소를 사용한다. 외부 API가 향후 필드를 추가하더라도 안전하도록
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}를 적용한다.
 *
 * <p>비즈니스 정책(random=0 → 422 매핑 등)은 본 DTO가 아닌 Phase 4 서비스 계층 책임이다.
 * 본 record는 외부 응답의 정확한 매핑만 담당한다.
 *
 * @param status csrng API 자체 처리 상태. "success" 외 값은 어댑터 계층에서 실패로 간주
 * @param min    csrng 요청 시 전달한 최소값 (회신값, 보통 0)
 * @param max    csrng 요청 시 전달한 최대값 (회신값, 보통 1)
 * @param random 발생한 난수 값 (구독/해지 트랜잭션 커밋·롤백 결정에 사용; 0 또는 1)
 */
@Schema(description = "csrng 외부 난수 API 응답 단건")
@JsonIgnoreProperties(ignoreUnknown = true)
public record CsrngResponse(
        @Schema(description = "csrng API 자체 처리 상태 문자열. \"success\"가 아니면 어댑터가 실패로 분류", example = "success")
        String status,

        @Schema(description = "csrng 요청 시 전달한 최소값(회신). ASSIGNMENT 예시에서 0", example = "0")
        Integer min,

        @Schema(description = "csrng 요청 시 전달한 최대값(회신). ASSIGNMENT 예시에서 1", example = "1")
        Integer max,

        @Schema(description = "발생한 난수 값 (0 또는 1). 비즈니스 정책에서 1=커밋, 0=롤백으로 해석", example = "1")
        Integer random
) {
}
