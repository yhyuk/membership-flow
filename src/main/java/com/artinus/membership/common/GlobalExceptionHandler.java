package com.artinus.membership.common;

import com.artinus.membership.common.ChannelPolicyViolationException;
import com.artinus.membership.common.ConcurrentModificationException;
import com.artinus.membership.common.ExternalValidationRejectedException;
import com.artinus.membership.common.ResourceNotFoundException;
import com.artinus.membership.subscription.IllegalStateTransitionException;
import com.artinus.membership.csrng.CsrngException;
import com.artinus.membership.common.ApiResponse;
import com.artinus.membership.common.ApiResponse.FieldError;
import com.artinus.membership.common.ErrorCode;
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

/**
 * 공통 ApiResponse 기반 글로벌 예외 처리기.
 *
 * <p>모든 에러 응답은 {@link ApiResponse#error(String, String)} 형식으로 통일된다.
 * HTTP 상태 코드는 ErrorCode 매트릭스를 따른다 (handoff §3.2 9개 상황 그대로 유지).</p>
 *
 * <pre>
 *  상황                                                 HTTP   ErrorCode
 *  ───────────────────────────────────────────────────────────────────────────
 *  @Valid 위반                                           400    VALIDATION_FAILED
 *  회원/채널 미존재                                       404    RESOURCE_NOT_FOUND
 *  상태 전이 정책 위반                                    422    INVALID_STATE_TRANSITION
 *  csrng random=0                                        422    EXTERNAL_VALIDATION_REJECTED
 *  채널 권한 위반                                         422    CHANNEL_POLICY_VIOLATION
 *  낙관락 / UNIQUE 동시 충돌                              409    CONCURRENT_MODIFICATION
 *  csrng 인프라 장애 (4xx/5xx/timeout/CB Open)             502    EXTERNAL_API_UNAVAILABLE
 *  분류되지 않은 서버 오류                                500    INTERNAL_ERROR
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 400 — @Valid 실패. */
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

    /** 404 — 회원/채널 미존재. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(ResourceNotFoundException ex) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
    }

    /** 400 — 도메인 입력 검증 실패 (path variable 등 @Valid 우회 경로). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return respond(ErrorCode.VALIDATION_FAILED, ex.getMessage());
    }

    /** 422 — 상태 전이 정책 위반. */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalTransition(IllegalStateTransitionException ex) {
        return respond(ErrorCode.INVALID_STATE_TRANSITION, ex.getMessage());
    }

    /** 422 — csrng random=0 등 외부 검증 거부. */
    @ExceptionHandler(ExternalValidationRejectedException.class)
    public ResponseEntity<ApiResponse<Object>> handleExternalRejected(ExternalValidationRejectedException ex) {
        return respond(ErrorCode.EXTERNAL_VALIDATION_REJECTED, ex.getMessage());
    }

    /** 422 — 채널 권한 위반. */
    @ExceptionHandler(ChannelPolicyViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleChannelViolation(ChannelPolicyViolationException ex) {
        return respond(ErrorCode.CHANNEL_POLICY_VIOLATION, ex.getMessage());
    }

    /** 409 — 낙관락 / UNIQUE 동시 충돌. */
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

    /** 502 — csrng 인프라 장애. */
    @ExceptionHandler({
            CsrngException.class,
            HttpServerErrorException.class,
            ResourceAccessException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleExternalUnavailable(RuntimeException ex) {
        return respond(ErrorCode.EXTERNAL_API_UNAVAILABLE, ex.getMessage());
    }

    /** 500 — 분류되지 않은 서버 내부 오류. */
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
