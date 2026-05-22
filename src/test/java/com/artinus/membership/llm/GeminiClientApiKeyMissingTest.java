package com.artinus.membership.llm;

import com.artinus.membership.subscription.domain.StateTransitionEvent;
import com.artinus.membership.history.domain.SubscriptionHistory;
import com.artinus.membership.subscription.domain.SubscriptionState;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GeminiClient}의 api-key 빈 문자열 케이스 검증.
 *
 * <p>빈 키 환경에서는 HTTP 호출 자체가 일어나지 않고 즉시 {@link GeminiException}이
 * 던져져야 한다 (handoff §빈 키 처리 결정). WireMock으로 외부 콜이 0회임을 검증한다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("GeminiClient — api-key 미설정 시 HTTP 호출 0회")
class GeminiClientApiKeyMissingTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private GeminiClient geminiClient;

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
        // base-url은 WireMock으로 향하지만 api-key가 빈 문자열이라 호출 자체가 일어나지 않아야 한다.
        registry.add("external.llm.gemini.base-url", () -> wireMockServer.baseUrl());
        registry.add("external.llm.gemini.api-key", () -> "");
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    @DisplayName("빈 api-key → GeminiException 즉시 throw, WireMock 호출 0회")
    void emptyApiKey_throwsImmediatelyWithoutHttpCall() {
        assertThatThrownBy(() -> geminiClient.summarize(sampleHistories(), Map.of(1L, "HOMEPAGE")))
                .isInstanceOf(GeminiException.class)
                .hasMessageContaining("api-key not configured");

        wireMockServer.verify(0, anyRequestedFor(anyUrl()));
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
}
