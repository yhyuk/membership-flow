package com.artinus.membership.common.exception;

import com.artinus.membership.subscription.domain.SubscriptionState;
import com.artinus.membership.subscription.domain.SubscriptionStateLabel;

/** 현재 상태와 동일한 상태로의 변경 요청. 422 ALREADY_IN_TARGET_STATE. */
public class AlreadyInTargetStateException extends RuntimeException {

    private final SubscriptionState state;

    public AlreadyInTargetStateException(SubscriptionState state) {
        super("이미 " + SubscriptionStateLabel.of(state) + " 상태입니다.");
        this.state = state;
    }

    public SubscriptionState state() {
        return state;
    }
}
