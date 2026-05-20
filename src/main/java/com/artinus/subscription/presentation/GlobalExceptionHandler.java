package com.artinus.subscription.presentation;

import com.artinus.subscription.application.exception.ChannelPolicyViolationException;
import com.artinus.subscription.application.exception.ConcurrentModificationException;
import com.artinus.subscription.application.exception.ExternalValidationRejectedException;
import com.artinus.subscription.application.exception.ResourceNotFoundException;
import com.artinus.subscription.domain.IllegalStateTransitionException;
import com.artinus.subscription.external.csrng.CsrngException;
import com.artinus.subscription.presentation.error.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * RFC 7807 ProblemDetail 기반 글로벌 예외 처리기.
 *
 * <p>{@link ResponseEntityExceptionHandler}를 상속하여 Spring MVC 표준 예외(@Valid 실패 포함)도
 * 우리 코드 카탈로그에 맞춰 매핑한다.</p>
 *
 * <p>handoff §3.2 HTTP 상태 매트릭스 9 상황 매핑:
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

    /** 400 — @Valid 실패. ResponseEntityExceptionHandler 오버라이드 (Spring MVC 표준 진입점). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldErrorEntry)
                .toList();
        ProblemDetail body = build(ErrorCode.VALIDATION_FAILED, "요청 본문 검증에 실패했습니다.");
        body.setProperty("errors", errors);
        return new ResponseEntity<>(body, headers, ErrorCode.VALIDATION_FAILED.httpStatus());
    }

    /** 404 — 회원/채널 미존재. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail body = build(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
        body.setProperty("resourceType", ex.resourceType());
        body.setProperty("identifier", ex.identifier());
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.httpStatus()).body(body);
    }

    /** 422 — 상태 전이 정책 위반. */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ProblemDetail> handleIllegalTransition(IllegalStateTransitionException ex) {
        ProblemDetail body = build(ErrorCode.INVALID_STATE_TRANSITION, ex.getMessage());
        body.setProperty("currentState", String.valueOf(ex.currentState()));
        body.setProperty("event", String.valueOf(ex.event()));
        return ResponseEntity.status(ErrorCode.INVALID_STATE_TRANSITION.httpStatus()).body(body);
    }

    /** 422 — csrng random=0 등 외부 검증 거부. */
    @ExceptionHandler(ExternalValidationRejectedException.class)
    public ResponseEntity<ProblemDetail> handleExternalRejected(ExternalValidationRejectedException ex) {
        ProblemDetail body = build(ErrorCode.EXTERNAL_VALIDATION_REJECTED, ex.getMessage());
        return ResponseEntity.status(ErrorCode.EXTERNAL_VALIDATION_REJECTED.httpStatus()).body(body);
    }

    /** 422 — 채널 권한 위반. */
    @ExceptionHandler(ChannelPolicyViolationException.class)
    public ResponseEntity<ProblemDetail> handleChannelViolation(ChannelPolicyViolationException ex) {
        ProblemDetail body = build(ErrorCode.CHANNEL_POLICY_VIOLATION, ex.getMessage());
        return ResponseEntity.status(ErrorCode.CHANNEL_POLICY_VIOLATION.httpStatus()).body(body);
    }

    /** 409 — 도메인 ConcurrentModificationException + Spring 낙관락 예외(혹시 누락된 경로 대비). */
    @ExceptionHandler({
            ConcurrentModificationException.class,
            ObjectOptimisticLockingFailureException.class
    })
    public ResponseEntity<ProblemDetail> handleConcurrent(RuntimeException ex) {
        ProblemDetail body = build(ErrorCode.CONCURRENT_MODIFICATION, ex.getMessage());
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
    public ResponseEntity<ProblemDetail> handleExternalUnavailable(RuntimeException ex) {
        ProblemDetail body = build(ErrorCode.EXTERNAL_API_UNAVAILABLE, ex.getMessage());
        return ResponseEntity.status(ErrorCode.EXTERNAL_API_UNAVAILABLE.httpStatus()).body(body);
    }

    /** 500 — 분류되지 않은 서버 내부 오류. */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Throwable ex) {
        ProblemDetail body = build(ErrorCode.INTERNAL_ERROR,
                ex.getMessage() == null ? ErrorCode.INTERNAL_ERROR.defaultMessage() : ex.getMessage());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus()).body(body);
    }

    private static ProblemDetail build(ErrorCode code, String detail) {
        HttpStatus status = code.httpStatus();
        String safeDetail = (detail == null || detail.isBlank()) ? code.defaultMessage() : detail;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, safeDetail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code.name());
        return problem;
    }

    private static Map<String, String> toFieldErrorEntry(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "" : error.getDefaultMessage()
        );
    }
}
