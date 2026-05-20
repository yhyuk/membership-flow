package com.artinus.subscription.domain;

/**
 * 구독 상태 전이를 트리거하는 도메인 이벤트.
 *
 * <p>ASSIGNMENT.md (line 65-90)의 "구독하기 API"와 "구독 해지 API"는
 * 각각 목표 상태를 함께 받기 때문에, 이벤트를 (액션 × 목표 상태) 4종으로
 * 분해하여 표현한다. 이로써 {@link StateTransitionPolicy}가
 * 입력 (현재 상태, 이벤트) 1쌍만으로 결정적인 다음 상태를 산출할 수 있다.</p>
 *
 * <ul>
 *   <li>{@link #SUBSCRIBE_BASIC} — 일반 구독으로 가입/전환</li>
 *   <li>{@link #SUBSCRIBE_PREMIUM} — 프리미엄 구독으로 가입/전환</li>
 *   <li>{@link #UNSUBSCRIBE_BASIC} — 프리미엄에서 일반으로 다운그레이드 해지</li>
 *   <li>{@link #UNSUBSCRIBE_NONE} — 구독을 완전히 종료</li>
 * </ul>
 */
public enum StateTransitionEvent {

    /** 일반 구독으로 가입 또는 전환. */
    SUBSCRIBE_BASIC(ActionType.SUBSCRIBE, SubscriptionState.BASIC),

    /** 프리미엄 구독으로 가입 또는 전환. */
    SUBSCRIBE_PREMIUM(ActionType.SUBSCRIBE, SubscriptionState.PREMIUM),

    /** 프리미엄 → 일반으로 다운그레이드 해지. */
    UNSUBSCRIBE_BASIC(ActionType.UNSUBSCRIBE, SubscriptionState.BASIC),

    /** 구독을 완전히 종료. */
    UNSUBSCRIBE_NONE(ActionType.UNSUBSCRIBE, SubscriptionState.NONE);

    private final ActionType action;
    private final SubscriptionState targetState;

    StateTransitionEvent(ActionType action, SubscriptionState targetState) {
        this.action = action;
        this.targetState = targetState;
    }

    public ActionType action() {
        return action;
    }

    public SubscriptionState targetState() {
        return targetState;
    }

    /** 이벤트의 액션 분류. {@code subscription_history.event_type} 컬럼에 저장된다. */
    public enum ActionType {
        SUBSCRIBE,
        UNSUBSCRIBE
    }
}
