package com.artinus.membership.csrng;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * csrng 외부 난수 API 어댑터.
 *
 * <p>책임: HTTP 호출 + 응답 매핑 + 실패 분류만 담당한다.
 * <ul>
 *   <li>2xx + 정상 배열 → {@code random} 값 반환 (0 또는 1 그대로; 비즈니스 정책 적용 금지)</li>
 *   <li>2xx + 빈 배열/null → {@link CsrngException} (handoff M-3)</li>
 *   <li>2xx + status != "success" → {@link CsrngException}</li>
 *   <li>4xx → 즉시 {@link CsrngException} (재시도 무력 — application.yml ignore-exceptions)</li>
 *   <li>5xx → {@link org.springframework.web.client.HttpServerErrorException} → application.yml retry-exceptions에 의해 재시도, 최종 실패 시 본 클래스가 {@link CsrngException}으로 래핑</li>
 *   <li>IO/timeout → {@link org.springframework.web.client.ResourceAccessException} → 재시도 후 {@link CsrngException} 래핑</li>
 * </ul>
 *
 * <p>비즈니스 정책(random=0 → 422 EXTERNAL_VALIDATION_REJECTED 매핑)은 본 어댑터의 책임이 아니다.
 * 어댑터는 외부 API의 정확한 결과를 그대로 호출자에게 노출한다.
 *
 * <p>Resilience4j 적용:
 * <ul>
 *   <li>{@code @Retry(name = "csrng")} — 5xx 및 IO 실패 시 application.yml 설정대로 재시도</li>
 *   <li>{@code @CircuitBreaker(name = "csrng")} — 누적 실패율 임계 초과 시 OPEN 상태로 즉시 실패</li>
 * </ul>
 * Fallback 메서드는 작성하지 않는다 (fail-closed 정책, handoff §외부 API 격리).
 *
 * <p>구현 노트 — Spring AOP 프록시 동작:
 * Resilience4j 어노테이션은 외부 호출 시점에만 활성화된다. 따라서 메서드 내부에서 try-catch로
 * 예외를 변환하면 Retry/CircuitBreaker가 원본 예외 타입을 보지 못한다. 본 클래스는 retry 대상이 되어야 할
 * 원본 예외({@code HttpServerErrorException}, {@code ResourceAccessException})를 catch하지 않고
 * 그대로 외부로 던져 AOP가 가로채도록 한다. 최종 실패(retry 모두 소진) 시점의 예외는
 * 호출자(Phase 4 서비스)가 인지하기 쉽도록 {@link #wrapIfNeeded(RuntimeException)}로
 * {@link CsrngException}으로 정규화한다.
 * 단, 본 변환은 retry 사이클이 모두 끝난 후 호출자가 받는 시점에서만 의미가 있으므로
 * 어댑터 외부에서 catch 후 다시 변환하는 패턴 대신, 호출자가 보게 될 예외를 일관시키기 위해
 * 4xx와 응답 매핑 실패는 직접 CsrngException으로 던지고, 5xx/IO는 원본 예외를 던져 Retry 동작을 보장한다.
 * 그 결과 외부 노출 예외는 두 종류이며 — 모두 인스턴스가 {@code RuntimeException}이고
 * GlobalExceptionHandler(Phase 4)에서 둘 다 502로 매핑된다.
 */
@Component
public class CsrngClient {

    private static final ParameterizedTypeReference<List<CsrngResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    /** csrng 응답 success 토큰 (ASSIGNMENT.md §외부 API 응답 예시). */
    private static final String STATUS_SUCCESS = "success";

    /** ASSIGNMENT 호출 예시 쿼리스트링 (min=0&max=1). */
    private static final String QUERY_MIN_0_MAX_1 = "?min=0&max=1";

    private final RestClient csrngRestClient;

    public CsrngClient(@Qualifier("csrngRestClient") RestClient csrngRestClient) {
        this.csrngRestClient = csrngRestClient;
    }

    /**
     * ASSIGNMENT 명세대로 min=0, max=1 난수를 1회 받아 {@code random} 비트(0 또는 1)를 반환한다.
     *
     * <p>본 메서드는 비즈니스 의사결정을 하지 않는다. 호출자(Phase 4 서비스)가 반환값으로
     * 트랜잭션 커밋(1) / 롤백(0) 처리를 결정한다.
     *
     * @return csrng가 발생시킨 0 또는 1 정수
     * @throws CsrngException 4xx 응답, 빈 배열, status≠success, null random 등 응답-수준 실패
     * @throws org.springframework.web.client.HttpServerErrorException 5xx 응답 (Retry 모두 소진 후 호출자 전파)
     * @throws org.springframework.web.client.ResourceAccessException IO/timeout (Retry 모두 소진 후 호출자 전파)
     */
    @Retry(name = "csrng")
    @CircuitBreaker(name = "csrng")
    public int fetchRandomBit() {
        List<CsrngResponse> body = csrngRestClient.get()
                .uri(QUERY_MIN_0_MAX_1)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    // 4xx는 호출 측 잘못이므로 재시도 무의미 → CsrngException으로 즉시 fail.
                    // application.yml의 ignore-exceptions에 CsrngException이 등록되어 있어
                    // Retry가 본 예외를 가로채지 않고 즉시 호출자에게 전파한다.
                    throw new CsrngException(
                            "csrng client error: status=" + response.getStatusCode().value());
                })
                // 5xx는 onStatus로 가로채지 않는다 — Spring의 기본 HttpServerErrorException으로
                // 전파되어야 application.yml retry-exceptions에 의해 재시도된다.
                .body(RESPONSE_TYPE);

        if (body == null || body.isEmpty()) {
            // handoff M-3 — csrng가 빈 배열로 응답하는 경우 IndexOutOfBoundsException 방지
            throw new CsrngException("csrng returned empty body");
        }

        CsrngResponse first = body.get(0);
        if (first == null) {
            throw new CsrngException("csrng returned null element");
        }
        if (!STATUS_SUCCESS.equals(first.status())) {
            throw new CsrngException("csrng status not success: status=" + first.status());
        }
        Integer random = first.random();
        if (random == null) {
            throw new CsrngException("csrng returned null random field");
        }
        return random;
    }
}
