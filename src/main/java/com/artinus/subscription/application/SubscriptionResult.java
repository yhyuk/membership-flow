package com.artinus.subscription.application;

import com.artinus.subscription.domain.StateTransitionEvent;
import com.artinus.subscription.domain.SubscriptionState;

import java.time.LocalDateTime;

/**
 * 구독/해지 처리 결과.
 *
 * <p>Presentation 계층의 SubscriptionResponse로 매핑된다.</p>
 *
 * @param subscriptionId 구독 행 PK
 * @param memberId       회원 PK
 * @param channelCode    채널 코드
 * @param previousState  전이 직전 상태 (신규 회원/채널이면 NONE)
 * @param currentState   전이 직후 상태
 * @param actionType     SUBSCRIBE / UNSUBSCRIBE
 * @param occurredAt     처리 시각
 */
public record SubscriptionResult(
        Long subscriptionId,
        Long memberId,
        String channelCode,
        SubscriptionState previousState,
        SubscriptionState currentState,
        StateTransitionEvent.ActionType actionType,
        LocalDateTime occurredAt
) {
}
