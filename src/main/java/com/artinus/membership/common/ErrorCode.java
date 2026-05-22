package com.artinus.membership.common;

import org.springframework.http.HttpStatus;

/**
 * HTTP 상태 매트릭스 코드 카탈로그.
 *
 * <p>handoff §3.2 9개 상황을 enum 상수로 1:1 매핑.
 * {@link com.artinus.membership.common.GlobalExceptionHandler}에서
 * {@code ProblemDetail.setProperty("code", ...)} 확장 속성으로 응답에 첨부된다.</p>
 */
public enum ErrorCode {

    /** 입력 유효성(@Valid) 위반. */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력 값 검증에 실패했습니다."),

    /** 회원 또는 채널 미존재. */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),

    /** 상태 전이 매트릭스에 정의되지 않은 전이. */
    INVALID_STATE_TRANSITION(HttpStatus.UNPROCESSABLE_ENTITY, "허용되지 않은 상태 전이입니다."),

    /** csrng random=0 — 외부 검증 거부. */
    EXTERNAL_VALIDATION_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "외부 검증에 의해 거부되었습니다."),

    /** 채널 권한 정책 위반 (subscribable/unsubscribable 플래그 위반). */
    CHANNEL_POLICY_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "채널 정책을 위반한 요청입니다."),

    /** 낙관락 충돌 또는 UNIQUE 동시 위반. */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "동시 수정 충돌이 발생했습니다. 잠시 후 다시 시도해주세요."),

    /** csrng 인프라 장애 (5xx / IO / CB Open). */
    EXTERNAL_API_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "외부 API가 일시적으로 사용 불가합니다."),

    /** 분류되지 않은 서버 내부 오류. */
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
