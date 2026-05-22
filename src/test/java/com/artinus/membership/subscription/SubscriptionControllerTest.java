package com.artinus.membership.subscription;

import com.artinus.membership.common.ApiResponse;
import com.artinus.membership.subscription.SubscriptionService;
import com.artinus.membership.subscription.SubscriptionRequest;
import com.artinus.membership.subscription.SubscriptionResponse;
import com.artinus.membership.common.ConcurrentModificationException;
import com.artinus.membership.common.ExternalValidationRejectedException;
import com.artinus.membership.subscription.IllegalStateTransitionException;
import com.artinus.membership.subscription.StateTransitionEvent;
import com.artinus.membership.subscription.SubscriptionState;
import com.artinus.membership.csrng.CsrngException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SubscriptionController} 슬라이스 테스트.
 *
 * <p>공통 ApiResponse 래퍼 응답을 검증한다. 정상 응답은 HTTP 200 + code=SUCCESS로 통일,
 * 에러 응답은 HTTP 상태 매트릭스(handoff §3.2)를 유지하되 본문 구조는 ApiResponse.error 형식.</p>
 */
@WebMvcTest(SubscriptionController.class)
class SubscriptionControllerTest {

    private static final String ENDPOINT = "/api/v1/subscriptions";
    private static final String PHONE = "01012345678";
    private static final String CHANNEL = "HOMEPAGE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SubscriptionService subscriptionService;

    @Test
    void subscribeSuccessReturns200WithEnvelope() throws Exception {
        SubscriptionResponse result = new SubscriptionResponse(
                1L, 10L, CHANNEL, SubscriptionState.NONE, SubscriptionState.BASIC,
                StateTransitionEvent.ActionType.SUBSCRIBE, LocalDateTime.of(2026, 5, 20, 12, 0));
        when(subscriptionService.execute(any(SubscriptionRequest.class))).thenReturn(result);

        postJson(body(PHONE, CHANNEL, "BASIC"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data.subscriptionId").value(1))
                .andExpect(jsonPath("$.data.memberId").value(10))
                .andExpect(jsonPath("$.data.channelCode").value(CHANNEL))
                .andExpect(jsonPath("$.data.previousState").value("NONE"))
                .andExpect(jsonPath("$.data.currentState").value("BASIC"))
                .andExpect(jsonPath("$.data.actionType").value("SUBSCRIBE"));
    }

    @Test
    void unsubscribeSuccessReturns200WithEnvelope() throws Exception {
        SubscriptionResponse result = new SubscriptionResponse(
                1L, 10L, CHANNEL, SubscriptionState.PREMIUM, SubscriptionState.NONE,
                StateTransitionEvent.ActionType.UNSUBSCRIBE, LocalDateTime.of(2026, 5, 20, 12, 0));
        when(subscriptionService.execute(any(SubscriptionRequest.class))).thenReturn(result);

        postJson(body(PHONE, CHANNEL, "NONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.actionType").value("UNSUBSCRIBE"))
                .andExpect(jsonPath("$.data.currentState").value("NONE"));
    }

    @Test
    void invalidPhoneNumberReturns400WithEnvelope() throws Exception {
        postJson(body("0101234", CHANNEL, "BASIC"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청 본문 검증에 실패했습니다."))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").value("phoneNumber"));
    }

    @Test
    void missingTargetStateReturns400WithEnvelope() throws Exception {
        String json = "{\"phoneNumber\":\"" + PHONE + "\",\"channelCode\":\"" + CHANNEL + "\"}";
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'targetState')]").exists());
    }

    @Test
    void illegalStateTransitionReturns422() throws Exception {
        when(subscriptionService.execute(any(SubscriptionRequest.class)))
                .thenThrow(new IllegalStateTransitionException(
                        SubscriptionState.BASIC, StateTransitionEvent.SUBSCRIBE_BASIC));

        postJson(body(PHONE, CHANNEL, "BASIC"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void externalValidationRejectedReturns422() throws Exception {
        when(subscriptionService.execute(any(SubscriptionRequest.class)))
                .thenThrow(new ExternalValidationRejectedException("random=0"));

        postJson(body(PHONE, CHANNEL, "BASIC"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("EXTERNAL_VALIDATION_REJECTED"))
                .andExpect(jsonPath("$.message").value("random=0"));
    }

    @Test
    void csrngExceptionReturns502() throws Exception {
        when(subscriptionService.execute(any(SubscriptionRequest.class)))
                .thenThrow(new CsrngException("upstream 5xx"));

        postJson(body(PHONE, CHANNEL, "BASIC"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("EXTERNAL_API_UNAVAILABLE"));
    }

    @Test
    void concurrentModificationReturns409WithRetryAfter() throws Exception {
        when(subscriptionService.execute(any(SubscriptionRequest.class)))
                .thenThrow(new ConcurrentModificationException("optimistic lock"));

        postJson(body(PHONE, CHANNEL, "PREMIUM"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Retry-After", "1"));
    }

    private ResultActions postJson(String body) throws Exception {
        return mockMvc.perform(post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String body(String phone, String channel, String state) throws Exception {
        return objectMapper.writeValueAsString(new RequestBodyJson(phone, channel, state));
    }

    private record RequestBodyJson(String phoneNumber, String channelCode, String targetState) {
    }
}
