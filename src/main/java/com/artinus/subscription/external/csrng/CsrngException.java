package com.artinus.subscription.external.csrng;

/**
 * csrng 외부 API 호출 실패를 표현하는 도메인 예외.
 *
 * <p>fail-closed 정책에 따라 어댑터는 어떤 임의 기본값도 반환하지 않고
 * 모든 실패 경로(IO, timeout, 5xx, 4xx, 빈 배열, status != success 등)를
 * 본 예외 단일 타입으로 전파한다.
 *
 * <p>Phase 4 {@code GlobalExceptionHandler}에서 이를
 * 502 Bad Gateway(EXTERNAL_API_UNAVAILABLE) ProblemDetail로 변환한다 (handoff §3.2).
 *
 * <p>서브타입을 만들지 않는다 (오버엔지니어링 방지). 실패 원인의 세분화는 message로만 표현한다.
 */
public class CsrngException extends RuntimeException {

    public CsrngException(String message) {
        super(message);
    }

    public CsrngException(String message, Throwable cause) {
        super(message, cause);
    }
}
