package com.artinus.membership.integration;

import com.artinus.membership.csrng.CsrngClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ASSIGNMENT 예시 시나리오 End-to-End 통합 테스트 (실제 MySQL 8 + Flyway).
 *
 * <p>회원 단일 상태 모델 검증 — 채널을 가로질러도 같은 회원의 상태가 일관되게 흐르는지.
 * csrng는 외부 호출 불안정성과 random=0 우연 거부를 피하기 위해 Mock으로 1 고정.
 * Gemini는 키 미설정으로 DEGRADED 응답이 정상적으로 흡수되는지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SubscriptionScenarioIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.39")
            .withDatabaseName("subscription")
            .withUsername("subscription")
            .withPassword("subscription");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Gemini는 키 없이 DEGRADED 응답 검증.
        registry.add("external.llm.gemini.api-key", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CsrngClient csrngClient;

    @Test
    @DisplayName("ASSIGNMENT 예시 시나리오: HOMEPAGE/BASIC → MOBILE_APP/PREMIUM → CALL_CENTER/NONE 단일 회원 상태 흐름")
    void assignmentScenarioEndToEnd() throws Exception {
        when(csrngClient.fetchRandomBit()).thenReturn(1);
        String phone = "01099887766";

        // 1) HOMEPAGE/BASIC 신규 가입
        Long subscriptionId = postSubscription(phone, "HOMEPAGE", "BASIC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.previousState").value("NONE"))
                .andExpect(jsonPath("$.data.currentState").value("BASIC"))
                .andExpect(jsonPath("$.data.actionType").value("SUBSCRIBE"))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .transform(s -> readJsonLong(s, "/data/subscriptionId"));

        // 2) MOBILE_APP/PREMIUM 업그레이드 — 회원 단일 행 유지 (id 동일), 채널만 변경
        postSubscription(phone, "MOBILE_APP", "PREMIUM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscriptionId").value(subscriptionId))
                .andExpect(jsonPath("$.data.previousState").value("BASIC"))
                .andExpect(jsonPath("$.data.currentState").value("PREMIUM"))
                .andExpect(jsonPath("$.data.channelCode").value("MOBILE_APP"));

        // 3) CALL_CENTER/NONE 해지 — 회원 단일 상태 모델이라 채널을 가로질러도 PREMIUM에서 NONE으로
        postSubscription(phone, "CALL_CENTER", "NONE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscriptionId").value(subscriptionId))
                .andExpect(jsonPath("$.data.previousState").value("PREMIUM"))
                .andExpect(jsonPath("$.data.currentState").value("NONE"))
                .andExpect(jsonPath("$.data.actionType").value("UNSUBSCRIBE"));

        // 4) 이력 조회 — 3건 모두 적재, 키 미설정으로 DEGRADED + summary=null + 마스킹
        mockMvc.perform(get("/api/v1/members/" + phone + "/subscription-histories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.phoneNumber").value("010-****-7766"))
                .andExpect(jsonPath("$.data.histories.length()").value(3))
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.summary").doesNotExist());
    }

    @Test
    @DisplayName("동일 상태 재요청 → 422 ALREADY_IN_TARGET_STATE, csrng 미호출")
    void sameStateRejectedWithKoreanMessage() throws Exception {
        when(csrngClient.fetchRandomBit()).thenReturn(1);
        String phone = "01077665544";

        postSubscription(phone, "HOMEPAGE", "BASIC").andExpect(status().isOk());

        postSubscription(phone, "HOMEPAGE", "BASIC")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALREADY_IN_TARGET_STATE"))
                .andExpect(jsonPath("$.message").value("이미 일반 구독 상태입니다."));
    }

    @Test
    @DisplayName("구독 안 함 상태에서 해지 시도 → 422 NO_ACTIVE_SUBSCRIPTION")
    void unsubscribeFromNoneRejected() throws Exception {
        when(csrngClient.fetchRandomBit()).thenReturn(1);
        String phone = "01055443322";

        postSubscription(phone, "CALL_CENTER", "NONE")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_SUBSCRIPTION"))
                .andExpect(jsonPath("$.message").value("구독 중이 아니므로 해지할 수 없습니다."));
    }

    @Test
    @DisplayName("NAVER 채널은 구독만 가능 — 해지 의도 요청 시 채널 권한 위반 메시지")
    void unsupportedChannelReturnsKoreanMessage() throws Exception {
        when(csrngClient.fetchRandomBit()).thenReturn(1);
        String phone = "01033221100";
        // 사전에 BASIC 구독 (HOMEPAGE)
        postSubscription(phone, "HOMEPAGE", "BASIC").andExpect(status().isOk());

        // NAVER로 해지 시도 → unsubscribable=false라 차단
        postSubscription(phone, "NAVER", "NONE")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHANNEL_POLICY_VIOLATION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("네이버")));
    }

    private org.springframework.test.web.servlet.ResultActions postSubscription(
            String phone, String channel, String targetState) throws Exception {
        String body = String.format(
                "{\"phoneNumber\":\"%s\",\"channelCode\":\"%s\",\"targetState\":\"%s\"}",
                phone, channel, targetState);
        return mockMvc.perform(post("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Long readJsonLong(String json, String pointer) {
        try {
            JsonNode node = objectMapper.readTree(json).at(pointer);
            assertThat(node.isMissingNode()).as("pointer %s should exist", pointer).isFalse();
            return node.asLong();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
