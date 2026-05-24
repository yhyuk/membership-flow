package com.artinus.membership.common.exception;

/** 구독 중이 아닌 회원이 해지를 요청. 422 NO_ACTIVE_SUBSCRIPTION. */
public class NoActiveSubscriptionException extends RuntimeException {

    public NoActiveSubscriptionException() {
        super("구독 중이 아니므로 해지할 수 없습니다.");
    }
}
