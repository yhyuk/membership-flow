package com.artinus.membership.history;

import com.artinus.membership.history.application.HistoryService;
import com.artinus.membership.history.dto.SubscriptionHistoryResponse;
import com.artinus.membership.common.exception.ResourceNotFoundException;
import com.artinus.membership.subscription.domain.StateTransitionEvent;
import com.artinus.membership.subscription.domain.SubscriptionState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SubscriptionHistoryController} 슬라이스 테스트.
 *
 * <p>공통 ApiResponse 래퍼 검증 — 정상은 HTTP 200 + code=SUCCESS,
 * LLM DEGRADED 신호는 {@code data.status} 필드, 에러는 {@code code}/{@code message}.</p>
 */
@WebMvcTest(SubscriptionHistoryController.class)
class SubscriptionHistoryControllerTest {

    private static final String PHONE = "01012345678";
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 5, 20, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HistoryService historyService;

    @Test
    void normalResponse200WithSummary() throws Exception {
        when(historyService.getRecentHistories(any())).thenReturn(normalResponse());

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", PHONE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.memberId").value(57))
                .andExpect(jsonPath("$.data.phoneNumber").value("010-****-5678"))
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.summary").value("요약입니다."))
                .andExpect(jsonPath("$.data.histories[0].channelCode").value("HOMEPAGE"))
                .andExpect(jsonPath("$.data.histories[0].channelName").value("홈페이지"))
                .andExpect(jsonPath("$.data.histories[0].previousState").value("NONE"))
                .andExpect(jsonPath("$.data.histories[0].nextState").value("BASIC"))
                .andExpect(jsonPath("$.data.histories[0].eventType").value("SUBSCRIBE"));
    }

    @Test
    void degradedResponse200WithNullSummary() throws Exception {
        when(historyService.getRecentHistories(any())).thenReturn(degradedResponse());

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.summary").doesNotExist())
                .andExpect(jsonPath("$.data.histories").isArray())
                .andExpect(jsonPath("$.data.histories.length()").value(1));
    }

    @Test
    void emptyHistoryResponse200WithEmptyArray() throws Exception {
        when(historyService.getRecentHistories(any())).thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("EMPTY"))
                .andExpect(jsonPath("$.data.summary").doesNotExist())
                .andExpect(jsonPath("$.data.histories").isArray())
                .andExpect(jsonPath("$.data.histories.length()").value(0));
    }

    @Test
    void memberNotFoundReturns404WithEnvelope() throws Exception {
        when(historyService.getRecentHistories(any()))
                .thenThrow(new ResourceNotFoundException("Member", PHONE));

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", PHONE))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void invalidPhoneNumberReturns400WithEnvelope() throws Exception {
        when(historyService.getRecentHistories(any()))
                .thenThrow(new IllegalArgumentException("phoneNumber must match ^010\\d{8}$ after normalization"));

        mockMvc.perform(get("/api/v1/members/{phoneNumber}/subscription-histories", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").exists());
    }

    // ---------- 헬퍼 ----------

    private static SubscriptionHistoryResponse normalResponse() {
        return new SubscriptionHistoryResponse(
                57L,
                "010-****-5678",
                List.of(historyItem()),
                "요약입니다.",
                SubscriptionHistoryResponse.Status.NORMAL,
                FIXED_NOW);
    }

    private static SubscriptionHistoryResponse degradedResponse() {
        return new SubscriptionHistoryResponse(
                57L,
                "010-****-5678",
                List.of(historyItem()),
                null,
                SubscriptionHistoryResponse.Status.DEGRADED,
                FIXED_NOW);
    }

    private static SubscriptionHistoryResponse emptyResponse() {
        return new SubscriptionHistoryResponse(
                57L,
                "010-****-5678",
                List.of(),
                null,
                SubscriptionHistoryResponse.Status.EMPTY,
                FIXED_NOW);
    }

    private static SubscriptionHistoryResponse.HistoryItem historyItem() {
        return new SubscriptionHistoryResponse.HistoryItem(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                "HOMEPAGE",
                "홈페이지",
                SubscriptionState.NONE,
                SubscriptionState.BASIC,
                StateTransitionEvent.ActionType.SUBSCRIBE);
    }
}
