package com.artinus.membership.subscription.domain;

/** 구독 상태 전이를 트리거하는 이벤트 — (action × targetState) 4종. */
public enum StateTransitionEvent {

    SUBSCRIBE_BASIC(ActionType.SUBSCRIBE, SubscriptionState.BASIC),
    SUBSCRIBE_PREMIUM(ActionType.SUBSCRIBE, SubscriptionState.PREMIUM),
    UNSUBSCRIBE_BASIC(ActionType.UNSUBSCRIBE, SubscriptionState.BASIC),
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

    /** subscription_history.event_type 컬럼에 저장. */
    public enum ActionType {
        SUBSCRIBE,
        UNSUBSCRIBE
    }
}
