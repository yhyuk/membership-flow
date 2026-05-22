package com.artinus.membership.common;

/**
 * csrng 외부 API가 정상 응답(HTTP 200)을 반환했지만 비즈니스 검증을 거부한 경우.
 *
 * <p>구체적으로 {@code random=0} 응답일 때 발생한다(handoff §1.1).
 * csrng는 인프라적으로 정상이므로 502가 아니라 422 {@code EXTERNAL_VALIDATION_REJECTED}로 매핑된다(handoff §3.2).</p>
 */
public class ExternalValidationRejectedException extends RuntimeException {

    public ExternalValidationRejectedException(String message) {
        super(message);
    }
}
