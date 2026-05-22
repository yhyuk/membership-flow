package com.artinus.membership.subscription.domain;

/** 회원의 구독 상태. 한 회원은 항상 정확히 1개의 상태만 갖는다. */
public enum SubscriptionState {
    NONE,
    BASIC,
    PREMIUM
}
