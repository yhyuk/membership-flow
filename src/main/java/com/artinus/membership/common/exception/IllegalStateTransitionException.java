package com.artinus.membership.common.exception;

import com.artinus.membership.subscription.domain.StateTransitionEvent;
import com.artinus.membership.subscription.domain.SubscriptionState;

/** 허용되지 않은 상태 전이. 422 INVALID_STATE_TRANSITION으로 매핑. */
public class IllegalStateTransitionException extends RuntimeException {

    private final SubscriptionState currentState;
    private final StateTransitionEvent event;

    public IllegalStateTransitionException(SubscriptionState currentState, StateTransitionEvent event) {
        super(buildMessage(currentState, event));
        this.currentState = currentState;
        this.event = event;
    }

    public SubscriptionState currentState() {
        return currentState;
    }

    public StateTransitionEvent event() {
        return event;
    }

    private static String buildMessage(SubscriptionState currentState, StateTransitionEvent event) {
        return "Illegal state transition: current=" + currentState + ", event=" + event;
    }
}
