package com.artinus.membership.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 공통 API 응답 래퍼.
 *
 * <p>모든 응답은 본 구조로 통일된다.
 * <pre>
 * {
 *   "success": true,
 *   "data": {...} | null,
 *   "message": null | "사람이 읽는 메시지",
 *   "code": "SUCCESS" | "VALIDATION_FAILED" | ...,
 *   "timestamp": "2026-05-21T01:26:19.044521349Z"
 * }
 * </pre>
 *
 * <p>설계 결정:
 * <ul>
 *   <li>성공 응답의 HTTP 상태는 항상 200. 의미 분기는 {@code code}와 {@code data} 내부 필드로 표현.</li>
 *   <li>에러 응답의 HTTP 상태는 기존 ErrorCode 매트릭스 (400/404/409/422/502/500) 유지.</li>
 *   <li>{@code timestamp}는 UTC ISO-8601 (Instant.toString)으로 통일.</li>
 *   <li>{@code errors} 필드는 {@code @Valid} 위반 시에만 채워지는 선택 필드.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "ApiResponse", description = "공통 API 응답 래퍼")
public record ApiResponse<T>(
        @Schema(description = "처리 성공 여부", example = "true") boolean success,
        @Schema(description = "실제 응답 페이로드") T data,
        @Schema(description = "사람이 읽는 메시지 (성공 시 null)", example = "null", nullable = true) String message,
        @Schema(description = "응답 코드 (성공은 SUCCESS, 실패는 ErrorCode enum)", example = "SUCCESS") String code,
        @Schema(description = "응답 생성 시각 (UTC ISO-8601)", example = "2026-05-21T01:26:19.044521349Z") Instant timestamp,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "@Valid 필드별 오류 목록 (VALIDATION_FAILED 응답에만 포함)", nullable = true)
        List<FieldError> errors
) {

    public static final String CODE_SUCCESS = "SUCCESS";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, CODE_SUCCESS, Instant.now(), null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, message, code, Instant.now(), null);
    }

    public static <T> ApiResponse<T> error(String code, String message, List<FieldError> errors) {
        return new ApiResponse<>(false, null, message, code, Instant.now(), errors);
    }

    /** @Valid 필드 오류 단위. */
    @Schema(name = "ApiResponse.FieldError", description = "검증 실패 필드 정보")
    public record FieldError(
            @Schema(description = "오류 필드명", example = "phoneNumber") String field,
            @Schema(description = "오류 메시지", example = "phoneNumber must match ^010\\d{8}$") String message
    ) {
    }
}
