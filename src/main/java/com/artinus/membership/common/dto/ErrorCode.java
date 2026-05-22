package com.artinus.membership.common.dto;

import org.springframework.http.HttpStatus;

/** HTTP 상태 + 응답 코드 카탈로그. GlobalExceptionHandler가 본 enum으로 매핑. */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력 값 검증에 실패했습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    INVALID_STATE_TRANSITION(HttpStatus.UNPROCESSABLE_ENTITY, "허용되지 않은 상태 전이입니다."),
    EXTERNAL_VALIDATION_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "외부 검증에 의해 거부되었습니다."),
    CHANNEL_POLICY_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "채널 정책을 위반한 요청입니다."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "동시 수정 충돌이 발생했습니다. 잠시 후 다시 시도해주세요."),
    EXTERNAL_API_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "외부 API가 일시적으로 사용 불가합니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
