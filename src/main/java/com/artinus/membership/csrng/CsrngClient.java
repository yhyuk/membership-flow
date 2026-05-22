package com.artinus.membership.csrng;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** csrng 외부 난수 API 어댑터. Retry/CircuitBreaker는 application.yml의 "csrng" 인스턴스가 담당. */
@Component
public class CsrngClient {

    private static final ParameterizedTypeReference<List<CsrngResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final String STATUS_SUCCESS = "success";
    private static final String QUERY_MIN_0_MAX_1 = "?min=0&max=1";

    private final RestClient csrngRestClient;

    public CsrngClient(@Qualifier("csrngRestClient") RestClient csrngRestClient) {
        this.csrngRestClient = csrngRestClient;
    }

    /**
     * min=0, max=1 난수 1회 호출 후 0 또는 1 반환.
     *
     * @throws CsrngException                                                4xx / 빈 배열 / status≠success / random=null
     * @throws org.springframework.web.client.HttpServerErrorException       5xx (재시도 소진 후 전파)
     * @throws org.springframework.web.client.ResourceAccessException        IO/timeout (재시도 소진 후 전파)
     */
    @Retry(name = "csrng")
    @CircuitBreaker(name = "csrng")
    public int fetchRandomBit() {
        List<CsrngResponse> body = csrngRestClient.get()
                .uri(QUERY_MIN_0_MAX_1)
                .retrieve()
                // 4xx는 재시도 무의미하므로 즉시 CsrngException으로 fail (ignore-exceptions 등록됨).
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new CsrngException(
                            "csrng client error: status=" + response.getStatusCode().value());
                })
                .body(RESPONSE_TYPE);

        if (body == null || body.isEmpty()) {
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
