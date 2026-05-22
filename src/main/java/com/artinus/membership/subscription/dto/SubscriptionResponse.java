package com.artinus.membership.subscription.dto;

import com.artinus.membership.subscription.domain.StateTransitionEvent;
import com.artinus.membership.subscription.domain.SubscriptionState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 구독/해지 처리 결과 응답. */
@Schema(description = "구독/해지 처리 결과 응답")
public record SubscriptionResponse(

        @Schema(description = "구독 행 PK", example = "1024")
        Long subscriptionId,

        @Schema(description = "회원 PK", example = "57")
        Long memberId,

        @Schema(description = "처리된 채널 코드", example = "HOMEPAGE")
        String channelCode,

        @Schema(description = "전이 직전 상태 (신규는 NONE)", example = "NONE")
        SubscriptionState previousState,

        @Schema(description = "전이 직후 상태", example = "BASIC")
        SubscriptionState currentState,

        @Schema(description = "SUBSCRIBE / UNSUBSCRIBE", example = "SUBSCRIBE")
        StateTransitionEvent.ActionType actionType,

        @Schema(description = "처리 시각", example = "2026-05-20T14:30:00")
        LocalDateTime occurredAt
) {
}
