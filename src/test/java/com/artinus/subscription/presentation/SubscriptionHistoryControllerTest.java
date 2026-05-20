package com.artinus.subscription.presentation;

import com.artinus.subscription.application.HistoryResult;
import com.artinus.subscription.application.HistoryService;
import com.artinus.subscription.application.exception.ResourceNotFoundException;
import com.artinus.subscription.domain.Channel;
import com.artinus.subscription.domain.Member;
import com.artinus.subscription.domain.StateTransitionEvent;
import com.artinus.subscription.domain.SubscriptionHistory;
import com.artinus.subscription.domain.SubscriptionState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SubscriptionHistoryController} 슬라이스 테스트.
 *
 * <p>응답 시나리오 NORMAL/DEGRADED, 404(회원 미존재), 400(phoneNumber 형식 오류)을 검증한다.</p>
 */
@WebMvcTest(SubscriptionHistoryController.class)
@Import(SubscriptionHistoryControllerTest.FixedClockConfig.class)
class SubscriptionHistoryControllerTest {

    private static final String PHONE = "01012345678";
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 5, 20, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HistoryService historyService;

    @Test
    void normalResponse200WithSummary() throws Exception {
        when(historyService.getRecentHistories(any())).thenReturn(normalResult());

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", PHONE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.memberId").value(57))
                .andExpect(jsonPath("$.phoneNumber").value("010-****-5678"))
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andExpect(jsonPath("$.summary").value("요약입니다."))
                .andExpect(jsonPath("$.histories[0].channelCode").value("HOMEPAGE"))
                .andExpect(jsonPath("$.histories[0].channelName").value("홈페이지"))
                .andExpect(jsonPath("$.histories[0].previousState").value("NONE"))
                .andExpect(jsonPath("$.histories[0].nextState").value("BASIC"))
                .andExpect(jsonPath("$.histories[0].eventType").value("SUBSCRIBE"));
    }

    @Test
    void degradedResponse200WithNullSummary() throws Exception {
        when(historyService.getRecentHistories(any())).thenReturn(degradedResult());

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.histories").isArray())
                .andExpect(jsonPath("$.histories.length()").value(1));
    }

    @Test
    void emptyHistoryResponse200WithEmptyArray() throws Exception {
        when(historyService.getRecentHistories(any())).thenReturn(emptyResult());

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.histories").isArray())
                .andExpect(jsonPath("$.histories.length()").value(0));
    }

    @Test
    void memberNotFoundReturns404ProblemDetail() throws Exception {
        when(historyService.getRecentHistories(any()))
                .thenThrow(new ResourceNotFoundException("Member", PHONE));

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", PHONE))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidPhoneNumberReturns400ProblemDetail() throws Exception {
        when(historyService.getRecentHistories(any()))
                .thenThrow(new IllegalArgumentException("phoneNumber must match ^010\\d{8}$ after normalization"));

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400));
    }

    // ---------- 헬퍼 ----------

    private static HistoryResult normalResult() {
        return new HistoryResult(
                member(),
                List.of(history()),
                Map.of(1L, channel()),
                "요약입니다.",
                HistoryResult.Status.NORMAL);
    }

    private static HistoryResult degradedResult() {
        return new HistoryResult(
                member(),
                List.of(history()),
                Map.of(1L, channel()),
                null,
                HistoryResult.Status.DEGRADED);
    }

    private static HistoryResult emptyResult() {
        return new HistoryResult(
                member(),
                List.of(),
                Map.of(),
                null,
                HistoryResult.Status.EMPTY);
    }

    private static Member member() {
        return Member.builder()
                .id(57L).phoneNumber(PHONE).version(0L)
                .createdAt(FIXED_NOW).updatedAt(FIXED_NOW)
                .build();
    }

    private static Channel channel() {
        return Channel.builder()
                .id(1L).code("HOMEPAGE").name("홈페이지")
                .subscribable(true).unsubscribable(true)
                .createdAt(FIXED_NOW).build();
    }

    private static SubscriptionHistory history() {
        return SubscriptionHistory.builder()
                .id(1L).subscriptionId(100L).memberId(57L).channelId(1L)
                .previousState(SubscriptionState.NONE).nextState(SubscriptionState.BASIC)
                .eventType(StateTransitionEvent.ActionType.SUBSCRIBE)
                .occurredAt(LocalDateTime.of(2026, 1, 1, 10, 0))
                .build();
    }

    /** WebMvcTest 슬라이스에 Clock Bean 주입 — Controller가 Clock에 의존하므로. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(
                    FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant(),
                    ZoneId.systemDefault());
        }
    }
}
