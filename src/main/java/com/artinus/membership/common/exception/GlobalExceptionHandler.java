package com.artinus.membership.common.exception;

import com.artinus.membership.common.dto.ApiResponse;
import com.artinus.membership.common.dto.ApiResponse.FieldError;
import com.artinus.membership.common.dto.ErrorCode;
import com.artinus.membership.csrng.CsrngException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(),
                        fe.getDefaultMessage() == null ? "" : fe.getDefaultMessage()))
                .toList();
        ApiResponse<Object> body = ApiResponse.error(
                ErrorCode.VALIDATION_FAILED.name(),
                "요청 본문 검증에 실패했습니다.",
                fieldErrors);
        return new ResponseEntity<>(body, headers, ErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(ResourceNotFoundException ex) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return respond(ErrorCode.VALIDATION_FAILED, ex.getMessage());
    }

    @ExceptionHandler(AlreadyInTargetStateException.class)
    public ResponseEntity<ApiResponse<Object>> handleAlreadyInTargetState(AlreadyInTargetStateException ex) {
        return respond(ErrorCode.ALREADY_IN_TARGET_STATE, ex.getMessage());
    }

    @ExceptionHandler(NoActiveSubscriptionException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoActiveSubscription(NoActiveSubscriptionException ex) {
        return respond(ErrorCode.NO_ACTIVE_SUBSCRIPTION, ex.getMessage());
    }

    @ExceptionHandler(DowngradeNotAllowedException.class)
    public ResponseEntity<ApiResponse<Object>> handleDowngradeNotAllowed(DowngradeNotAllowedException ex) {
        return respond(ErrorCode.DOWNGRADE_NOT_ALLOWED, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalTransition(IllegalStateTransitionException ex) {
        return respond(ErrorCode.INVALID_STATE_TRANSITION, ex.getMessage());
    }

    @ExceptionHandler(ExternalValidationRejectedException.class)
    public ResponseEntity<ApiResponse<Object>> handleExternalRejected(ExternalValidationRejectedException ex) {
        return respond(ErrorCode.EXTERNAL_VALIDATION_REJECTED, ex.getMessage());
    }

    @ExceptionHandler(ChannelPolicyViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleChannelViolation(ChannelPolicyViolationException ex) {
        return respond(ErrorCode.CHANNEL_POLICY_VIOLATION, ex.getMessage());
    }

    @ExceptionHandler({
            ConcurrentModificationException.class,
            ObjectOptimisticLockingFailureException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleConcurrent(RuntimeException ex) {
        ApiResponse<Object> body = ApiResponse.error(
                ErrorCode.CONCURRENT_MODIFICATION.name(),
                fallbackMessage(ErrorCode.CONCURRENT_MODIFICATION, ex.getMessage()));
        return ResponseEntity.status(ErrorCode.CONCURRENT_MODIFICATION.httpStatus())
                .header("Retry-After", "1")
                .body(body);
    }

    @ExceptionHandler({
            CsrngException.class,
            HttpServerErrorException.class,
            ResourceAccessException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleExternalUnavailable(RuntimeException ex) {
        return respond(ErrorCode.EXTERNAL_API_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Throwable ex) {
        return respond(ErrorCode.INTERNAL_ERROR, ex.getMessage());
    }

    private static ResponseEntity<ApiResponse<Object>> respond(ErrorCode code, String message) {
        ApiResponse<Object> body = ApiResponse.error(code.name(), fallbackMessage(code, message));
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    private static String fallbackMessage(ErrorCode code, String raw) {
        return (raw == null || raw.isBlank()) ? code.defaultMessage() : raw;
    }
}
