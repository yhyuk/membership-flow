package com.artinus.membership.csrng;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * csrng 어댑터 통합 테스트.
 *
 * <p>WireMock으로 csrng API를 격리하고, Resilience4j Retry/CircuitBreaker가 적용된
 * 실제 Spring 컨텍스트에서 6+ 시나리오를 검증한다.
 *
 * <p>각 테스트 사이 CircuitBreaker 상태를 reset 하여 상태 누적이 시나리오 간 영향을 주지 않도록 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("CsrngClient 통합 (WireMock + Resilience4j)")
class CsrngClientTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private CsrngClient csrngClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    /**
     * external.csrng.base-url을 WireMock 동적 포트로 override.
     * base-url에 path("/csrng")까지 포함시켜 어댑터의 쿼리스트링("?min=0&max=1")과 합쳐 호출되도록 한다.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("external.csrng.base-url",
                () -> wireMockServer.baseUrl() + "/csrng");
        // 5xx/timeout 시나리오 안정성을 위해 read timeout을 짧게 유지
        registry.add("external.csrng.connect-timeout-ms", () -> "500");
        registry.add("external.csrng.read-timeout-ms", () -> "1000");
    }

    @BeforeEach
    void resetState() {
        wireMockServer.resetAll();
        circuitBreakerRegistry.circuitBreaker("csrng").reset();
    }

    @Test
    @DisplayName("200 + random=1 → 1 반환")
    void success_random_1() {
        stubOk("[{\"status\":\"success\",\"min\":0,\"max\":1,\"random\":1}]");

        int result = csrngClient.fetchRandomBit();

        Assertions.assertThat(result).isEqualTo(1);
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/csrng")));
    }

    @Test
    @DisplayName("200 + random=0 → 0 반환 (비즈니스 정책 미적용 — 어댑터는 그대로 노출)")
    void success_random_0_returnedAsIs() {
        stubOk("[{\"status\":\"success\",\"min\":0,\"max\":1,\"random\":0}]");

        int result = csrngClient.fetchRandomBit();

        // random=0 → 422 매핑은 Phase 4 서비스 책임. 어댑터는 0을 그대로 반환해야 한다.
        Assertions.assertThat(result).isEqualTo(0);
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/csrng")));
    }

    @Test
    @DisplayName("200 + 빈 배열 → CsrngException (handoff M-3)")
    void emptyArrayBody_throwsCsrngException() {
        stubOk("[]");

        Assertions.assertThatThrownBy(() -> csrngClient.fetchRandomBit())
                .isInstanceOf(CsrngException.class)
                .hasMessageContaining("empty body");

        // 빈 배열은 어댑터 응답-수준 실패 → CsrngException 즉시 throw (재시도 무력화: ignore-exceptions)
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/csrng")));
    }

    @Test
    @DisplayName("5xx 응답 → Retry 3회 후 호출자에 전파")
    void serverError_retriesThreeTimes() {
        wireMockServer.stubFor(get(urlPathEqualTo("/csrng"))
                .willReturn(aResponse().withStatus(503).withBody("svc unavailable")));

        // HttpServerErrorException은 retry-exceptions 매칭 → 3회 시도 후 호출자에 전파.
        // (어댑터가 별도 catch로 CsrngException 변환을 하지 않으므로 원본 타입이 그대로 노출됨;
        //  GlobalExceptionHandler(Phase 4)가 502로 통일 매핑한다.)
        Assertions.assertThatThrownBy(() -> csrngClient.fetchRandomBit())
                .isInstanceOf(RuntimeException.class);

        wireMockServer.verify(3, getRequestedFor(urlPathEqualTo("/csrng")));
    }

    @Test
    @DisplayName("read timeout (fixed delay) → Retry 3회 후 호출자에 전파")
    void readTimeout_retriesThreeTimes() {
        wireMockServer.stubFor(get(urlPathEqualTo("/csrng"))
                .willReturn(aResponse()
                        .withFixedDelay(3000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"status\":\"success\",\"min\":0,\"max\":1,\"random\":1}]")));

        Assertions.assertThatThrownBy(() -> csrngClient.fetchRandomBit())
                .isInstanceOfAny(ResourceAccessException.class, RuntimeException.class);

        wireMockServer.verify(3, getRequestedFor(urlPathEqualTo("/csrng")));
    }

    @Test
    @DisplayName("4xx 응답 → 재시도 없이 즉시 CsrngException (ignore-exceptions)")
    void clientError_noRetry() {
        wireMockServer.stubFor(get(urlPathEqualTo("/csrng"))
                .willReturn(aResponse().withStatus(400).withBody("bad req")));

        Assertions.assertThatThrownBy(() -> csrngClient.fetchRandomBit())
                .isInstanceOf(CsrngException.class)
                .hasMessageContaining("client error");

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/csrng")));
    }

    @Test
    @DisplayName("status != success → CsrngException (즉시)")
    void statusNotSuccess_throwsCsrngException() {
        stubOk("[{\"status\":\"failure\",\"min\":0,\"max\":1,\"random\":1}]");

        Assertions.assertThatThrownBy(() -> csrngClient.fetchRandomBit())
                .isInstanceOf(CsrngException.class)
                .hasMessageContaining("status not success");

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/csrng")));
    }

    @Test
    @DisplayName("CircuitBreaker — 5xx 반복으로 OPEN 진입 후 즉시 실패")
    void circuitBreaker_opensAfterRepeatedFailures() {
        wireMockServer.stubFor(get(urlPathEqualTo("/csrng"))
                .willReturn(aResponse().withStatus(503).withBody("svc unavailable")));

        // sliding-window-size=10, minimum-number-of-calls=5, failure-rate-threshold=50%
        // 매 호출당 retry 3회 → 1회의 fetchRandomBit() 호출은 CB에서 1건의 실패로 카운트되는 것이 아니라
        // CircuitBreaker가 실제로 본 호출(메서드 진입 단위)당 1회만 기록한다.
        // (Retry와 CircuitBreaker는 서로 독립된 카운터를 가짐.)
        // 최소 5회 메서드 호출 시점에 임계 도달 → 6회차부터 CallNotPermittedException으로 즉시 실패.
        for (int i = 0; i < 5; i++) {
            try {
                csrngClient.fetchRandomBit();
            } catch (RuntimeException ignored) {
                // 누적 실패 카운트만 발생시키면 됨
            }
        }
        // 위 5회 호출에서 각 3회 retry = 15회 HTTP 호출
        wireMockServer.verify(15, getRequestedFor(urlPathEqualTo("/csrng")));

        // 다음(6회차) 호출은 CB OPEN으로 HTTP 호출 자체가 발생하지 않아야 함
        wireMockServer.resetRequests();
        Assertions.assertThatThrownBy(() -> csrngClient.fetchRandomBit())
                .isInstanceOf(RuntimeException.class);
        // CB OPEN 상태에서는 HTTP 호출이 0회여야 한다
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/csrng")));
    }

    private void stubOk(String jsonBody) {
        wireMockServer.stubFor(get(urlPathEqualTo("/csrng"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonBody)));
    }
}
