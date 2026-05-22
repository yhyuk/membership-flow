package com.artinus.membership.common.exception;

/** 낙관락 충돌 또는 UNIQUE 동시 위반. 표준 java.util.ConcurrentModificationException과 무관. */
public class ConcurrentModificationException extends RuntimeException {

    public ConcurrentModificationException(String message) {
        super(message);
    }

    public ConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
