package com.artinus.subscription.presentation.dto;

import com.artinus.subscription.application.SubscriptionResult;
import com.artinus.subscription.domain.StateTransitionEvent;
import com.artinus.subscription.domain.SubscriptionState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 구독/해지 처리 결과 응답.
 *
 * <p>이력 적재 직후의 스냅샷을 반환한다. 클라이언트가 후속 호출 없이 결과를 확인할 수 있도록
 * previousState/currentState를 모두 노출한다.</p>
 */
@Schema(description = "구독/해지 처리 결과 응답")
public record SubscriptionResponse(

        @Schema(description = "이 회원×채널의 구독 행 PK", example = "1024")
        Long subscriptionId,

        @Schema(description = "회원 PK", example = "57")
        Long memberId,

        @Schema(description = "처리된 채널 코드", example = "HOMEPAGE")
        String channelCode,

        @Schema(description = "전이 직전 상태. 신규 회원/채널이면 NONE", example = "NONE")
        SubscriptionState previousState,

        @Schema(description = "전이 직후 현재 상태", example = "BASIC")
        SubscriptionState currentState,

        @Schema(description = "처리된 액션 분류(SUBSCRIBE / UNSUBSCRIBE)", example = "SUBSCRIBE")
        StateTransitionEvent.ActionType actionType,

        @Schema(description = "처리 시각 (서버 시계 기준)", example = "2026-05-20T14:30:00")
        LocalDateTime occurredAt
) {

    public static SubscriptionResponse from(SubscriptionResult result) {
        return new SubscriptionResponse(
                result.subscriptionId(),
                result.memberId(),
                result.channelCode(),
                result.previousState(),
                result.currentState(),
                result.actionType(),
                result.occurredAt());
    }
}
