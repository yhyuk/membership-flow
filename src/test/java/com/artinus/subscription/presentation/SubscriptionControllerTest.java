package com.artinus.subscription.presentation;

import com.artinus.subscription.application.SubscriptionCommand;
import com.artinus.subscription.application.SubscriptionResult;
import com.artinus.subscription.application.SubscriptionService;
import com.artinus.subscription.application.exception.ConcurrentModificationException;
import com.artinus.subscription.application.exception.ExternalValidationRejectedException;
import com.artinus.subscription.domain.IllegalStateTransitionException;
import com.artinus.subscription.domain.StateTransitionEvent;
import com.artinus.subscription.domain.SubscriptionState;
import com.artinus.subscription.external.csrng.CsrngException;
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
 * <p>ProblemDetail(application/problem+json) 응답 + ErrorCode 매핑을 핸드오프 §3.2 매트릭스대로 검증한다.</p>
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
    void subscribeSuccessReturns201() throws Exception {
        SubscriptionResult result = new SubscriptionResult(
                1L, 10L, CHANNEL, SubscriptionState.NONE, SubscriptionState.BASIC,
                StateTransitionEvent.ActionType.SUBSCRIBE, LocalDateTime.of(2026, 5, 20, 12, 0));
        when(subscriptionService.execute(any(SubscriptionCommand.class))).thenReturn(result);

        postJson(body(PHONE, CHANNEL, "BASIC"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.subscriptionId").value(1))
                .andExpect(jsonPath("$.memberId").value(10))
                .andExpect(jsonPath("$.channelCode").value(CHANNEL))
                .andExpect(jsonPath("$.previousState").value("NONE"))
                .andExpect(jsonPath("$.currentState").value("BASIC"))
                .andExpect(jsonPath("$.actionType").value("SUBSCRIBE"));
    }

    @Test
    void unsubscribeSuccessReturns200() throws Exception {
        SubscriptionResult result = new SubscriptionResult(
                1L, 10L, CHANNEL, SubscriptionState.PREMIUM, SubscriptionState.NONE,
                StateTransitionEvent.ActionType.UNSUBSCRIBE, LocalDateTime.of(2026, 5, 20, 12, 0));
        when(subscriptionService.execute(any(SubscriptionCommand.class))).thenReturn(result);

        postJson(body(PHONE, CHANNEL, "NONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value("UNSUBSCRIBE"))
                .andExpect(jsonPath("$.currentState").value("NONE"));
    }

    @Test
    void invalidPhoneNumberReturns400ProblemDetail() throws Exception {
        postJson(body("0101234", CHANNEL, "BASIC"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").value("phoneNumber"));
    }

    @Test
    void missingTargetStateReturns400ProblemDetail() throws Exception {
        String json = "{\"phoneNumber\":\"" + PHONE + "\",\"channelCode\":\"" + CHANNEL + "\"}";
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'targetState')]").exists());
    }

    @Test
    void illegalStateTransitionReturns422() throws Exception {
        when(subscriptionService.execute(any(SubscriptionCommand.class)))
                .thenThrow(new IllegalStateTransitionException(
                        SubscriptionState.BASIC, StateTransitionEvent.SUBSCRIBE_BASIC));

        postJson(body(PHONE, CHANNEL, "BASIC"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.currentState").value("BASIC"));
    }

    @Test
    void externalValidationRejectedReturns422() throws Exception {
        when(subscriptionService.execute(any(SubscriptionCommand.class)))
                .thenThrow(new ExternalValidationRejectedException("random=0"));

        postJson(body(PHONE, CHANNEL, "BASIC"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("EXTERNAL_VALIDATION_REJECTED"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void csrngExceptionReturns502() throws Exception {
        when(subscriptionService.execute(any(SubscriptionCommand.class)))
                .thenThrow(new CsrngException("upstream 5xx"));

        postJson(body(PHONE, CHANNEL, "BASIC"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("EXTERNAL_API_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void concurrentModificationReturns409WithRetryAfter() throws Exception {
        when(subscriptionService.execute(any(SubscriptionCommand.class)))
                .thenThrow(new ConcurrentModificationException("optimistic lock"));

        postJson(body(PHONE, CHANNEL, "PREMIUM"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.status").value(409))
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

    /** 테스트 전용 JSON 직렬화 헬퍼 — DTO record를 직접 사용하면 validation이 클라이언트 측에서 일어남. */
    private record RequestBodyJson(String phoneNumber, String channelCode, String targetState) {
    }
}
