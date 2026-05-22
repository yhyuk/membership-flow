package com.artinus.membership.csrng;

/** csrng 호출 실패(4xx, 빈 배열, status≠success 등). GlobalExceptionHandler가 502로 매핑한다. */
public class CsrngException extends RuntimeException {

    public CsrngException(String message) {
        super(message);
    }

    public CsrngException(String message, Throwable cause) {
        super(message, cause);
    }
}
