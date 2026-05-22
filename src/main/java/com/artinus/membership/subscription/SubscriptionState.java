package com.artinus.membership.subscription;

/**
 * 회원의 구독 상태.
 *
 * <p>ASSIGNMENT.md (line 13-20)의 3가지 상태를 정확히 매핑한다.
 * 한 회원은 항상 정확히 1개의 상태만 갖는다.</p>
 */
public enum SubscriptionState {

    /** 구독하지 않은 상태. 신규 회원의 기본 상태. */
    NONE,

    /** 일반 등급 구독 상태. */
    BASIC,

    /** 프리미엄 등급 구독 상태. */
    PREMIUM
}
