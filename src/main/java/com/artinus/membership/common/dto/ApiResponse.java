package com.artinus.membership.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 공통 API 응답 래퍼. 성공/실패 모두 본 구조로 통일. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "ApiResponse", description = "공통 API 응답 래퍼")
public record ApiResponse<T>(
        @Schema(description = "처리 성공 여부", example = "true") boolean success,
        @Schema(description = "응답 페이로드") T data,
        @Schema(description = "응답 메시지", nullable = true) String message,
        @Schema(description = "응답 코드 (SUCCESS / ErrorCode)", example = "SUCCESS") String code,
        @Schema(description = "응답 생성 시각 (UTC ISO-8601)", example = "2026-05-21T01:26:19.044Z") Instant timestamp,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "@Valid 필드별 오류 (VALIDATION_FAILED에만 포함)", nullable = true)
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

    /** @Valid 필드 오류. */
    @Schema(name = "ApiResponse.FieldError", description = "검증 실패 필드 정보")
    public record FieldError(
            @Schema(description = "오류 필드명", example = "phoneNumber") String field,
            @Schema(description = "오류 메시지", example = "phoneNumber must match ^010\\d{8}$") String message
    ) {
    }
}
