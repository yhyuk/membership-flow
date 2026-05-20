package com.artinus.subscription.external.llm;

import com.artinus.subscription.domain.StateTransitionEvent;
import com.artinus.subscription.domain.SubscriptionHistory;
import com.artinus.subscription.domain.SubscriptionState;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GeminiClient} 통합 테스트 (WireMock + Resilience4j).
 *
 * <p>Gemini API의 generateContent endpoint를 WireMock으로 격리하여 6+ 시나리오를 검증한다.</p>
 *
 * <p>각 테스트 사이 CircuitBreaker 상태를 reset하여 누적이 시나리오 간 영향을 주지 않도록 한다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("GeminiClient 통합 (WireMock + Resilience4j)")
class GeminiClientTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private GeminiClient geminiClient;

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

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("external.llm.gemini.base-url", () -> wireMockServer.baseUrl());
        registry.add("external.llm.gemini.api-key", () -> "test-key");
        registry.add("external.llm.gemini.connect-timeout-ms", () -> "500");
        registry.add("external.llm.gemini.read-timeout-ms", () -> "1000");
    }

    @BeforeEach
    void resetState() {
        wireMockServer.resetAll();
        circuitBreakerRegistry.circuitBreaker("gemini").reset();
    }

    @Test
    @DisplayName("200 + 정상 candidates → 텍스트 반환, HTTP 호출 1회")
    void success_returnsText() {
        wireMockServer.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "candidates": [
                                    {
                                      "content": {
                                        "role": "model",
                                        "parts": [{"text": "테스트 요약입니다."}]
                                      },
                                      "finishReason": "STOP"
                                    }
                                  ]
                                }
                                """)));

        String result = geminiClient.summarize(sampleHistories(), sampleChannelCodes());

        assertThat(result).isEqualTo("테스트 요약입니다.");
        wireMockServer.verify(1, postRequestedFor(urlPathMatching("/v1beta/models/.*:generateContent")));
    }

    @Test
    @DisplayName("200 + 빈 candidates → GeminiException, 재시도 없음")
    void emptyCandidates_throwsGeminiException() {
        wireMockServer.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"candidates\": []}")));

        assertThatThrownBy(() -> geminiClient.summarize(sampleHistories(), sampleChannelCodes()))
                .isInstanceOf(GeminiException.class)
                .hasMessageContaining("empty");

        // GeminiException은 ignore-exceptions이므로 재시도 0회 — 단 1회만 호출
        wireMockServer.verify(1, postRequestedFor(urlPathMatching("/v1beta/models/.*:generateContent")));
    }

    @Test
    @DisplayName("200 + finishReason=SAFETY → GeminiException (안전 필터 차단)")
    void safetyFilter_throwsGeminiException() {
        wireMockServer.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "candidates": [
                                    {
                                      "content": {"role": "model", "parts": [{"text": ""}]},
                                      "finishReason": "SAFETY"
                                    }
                                  ]
                                }
                                """)));

        assertThatThrownBy(() -> geminiClient.summarize(sampleHistories(), sampleChannelCodes()))
                .isInstanceOf(GeminiException.class)
                .hasMessageContaining("safety filter");

        wireMockServer.verify(1, postRequestedFor(urlPathMatching("/v1beta/models/.*:generateContent")));
    }

    @Test
    @DisplayName("5xx → Retry 2회 후 GeminiException(또는 HttpServerErrorException) 전파")
    void serverError_retriesTwice() {
        wireMockServer.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse().withStatus(503).withBody("svc unavailable")));

        // HttpServerErrorException은 retry-exceptions 매칭 → max-attempts=2 (총 2회) 후 전파.
        // GlobalExceptionHandler의 502 매핑은 본 Phase에서 무관 — HistoryService가 catch하여 DEGRADED 응답.
        assertThatThrownBy(() -> geminiClient.summarize(sampleHistories(), sampleChannelCodes()))
                .isInstanceOf(RuntimeException.class);

        wireMockServer.verify(2, postRequestedFor(urlPathMatching("/v1beta/models/.*:generateContent")));
    }

    @Test
    @DisplayName("read timeout (ResourceAccessException) → Retry → 호출자에 RuntimeException 전파")
    void readTimeout_retriesAndPropagates() {
        // read-timeout-ms=1000보다 충분히 긴 fixedDelay로 timeout이 결정적으로 발생하도록 한다.
        wireMockServer.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse()
                        .withFixedDelay(3000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"candidates\":[]}")));

        assertThatThrownBy(() -> geminiClient.summarize(sampleHistories(), sampleChannelCodes()))
                .isInstanceOf(RuntimeException.class);

        // Retry 동작 자체는 5xx 시나리오에서 max-attempts=2 검증이 되었으므로,
        // read timeout은 "예외가 호출자에 전파"되는 것만 확정 검증한다.
        // WireMock + fixedDelay + Java HttpClient 조합에서 두 번째 시도의 timing-sensitive 거동을
        // 결정적으로 강제하기 어려우므로 호출 횟수는 1회 이상으로만 확정.
        wireMockServer.verify(
                com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly(1),
                postRequestedFor(urlPathMatching("/v1beta/models/.*:generateContent")));
    }

    @Test
    @DisplayName("4xx → 재시도 없이 즉시 GeminiException")
    void clientError_noRetry() {
        wireMockServer.stubFor(post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(aResponse().withStatus(400).withBody("bad req")));

        assertThatThrownBy(() -> geminiClient.summarize(sampleHistories(), sampleChannelCodes()))
                .isInstanceOf(GeminiException.class)
                .hasMessageContaining("client error");

        wireMockServer.verify(1, postRequestedFor(urlPathMatching("/v1beta/models/.*:generateContent")));
    }

    private static List<SubscriptionHistory> sampleHistories() {
        return List.of(SubscriptionHistory.builder()
                .id(1L)
                .subscriptionId(100L)
                .memberId(10L)
                .channelId(1L)
                .previousState(SubscriptionState.NONE)
                .nextState(SubscriptionState.BASIC)
                .eventType(StateTransitionEvent.ActionType.SUBSCRIBE)
                .occurredAt(LocalDateTime.of(2026, 1, 1, 10, 0))
                .build());
    }

    private static Map<Long, String> sampleChannelCodes() {
        return Map.of(1L, "HOMEPAGE");
    }
}
