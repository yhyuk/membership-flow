package com.artinus.membership.common.exception;

import com.artinus.membership.subscription.domain.SubscriptionState;
import com.artinus.membership.subscription.domain.SubscriptionStateLabel;

/** 프리미엄에서 일반으로의 다운그레이드는 구독 API로 불가. 422 DOWNGRADE_NOT_ALLOWED. */
public class DowngradeNotAllowedException extends RuntimeException {

    private final SubscriptionState currentState;
    private final SubscriptionState targetState;

    public DowngradeNotAllowedException(SubscriptionState currentState, SubscriptionState targetState) {
        super(SubscriptionStateLabel.of(currentState) + "에서 "
                + SubscriptionStateLabel.of(targetState) + "(으)로 다운그레이드할 수 없습니다. 해지 API를 이용해 주세요.");
        this.currentState = currentState;
        this.targetState = targetState;
    }

    public SubscriptionState currentState() {
        return currentState;
    }

    public SubscriptionState targetState() {
        return targetState;
    }
}
