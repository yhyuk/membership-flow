package com.artinus.membership.common.exception;

/** csrng가 정상 응답했으나 비즈니스 거부(random=0). 422 EXTERNAL_VALIDATION_REJECTED. */
public class ExternalValidationRejectedException extends RuntimeException {

    public ExternalValidationRejectedException(String message) {
        super(message);
    }
}
