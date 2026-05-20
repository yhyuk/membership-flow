package com.artinus.subscription.application.exception;

/**
 * 낙관락 충돌 또는 UNIQUE 제약 위반 등 동시 수정 충돌을 통합 표현하는 도메인 예외.
 *
 * <p>handoff §3.2 매핑: 409 {@code CONCURRENT_MODIFICATION}.</p>
 *
 * <p>JPA의 {@code ObjectOptimisticLockingFailureException}와 JDBC의
 * {@code DataIntegrityViolationException}(UNIQUE 위반)을 서비스 계층에서 모두 본 예외로 변환한다.</p>
 *
 * <p>주의: 본 클래스는 표준 {@code java.util.ConcurrentModificationException}이 아닌
 * 도메인 전용 예외이다. 동일한 이름이지만 패키지가 다르다.</p>
 */
public class ConcurrentModificationException extends RuntimeException {

    public ConcurrentModificationException(String message) {
        super(message);
    }

    public ConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
