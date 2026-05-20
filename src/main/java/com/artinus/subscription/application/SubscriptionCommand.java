package com.artinus.subscription.application;

import com.artinus.subscription.domain.SubscriptionState;

/**
 * 구독/해지 단일 요청을 표현하는 명령 객체.
 *
 * <p>ASSIGNMENT 명세(line 67, 81)에 따라 입력은 (휴대폰번호, 채널 코드, 변경할 구독 상태) 3종이다.
 * 액션(SUBSCRIBE / UNSUBSCRIBE)은 현재 상태와 {@code targetState}를 비교하여 서비스가 산출한다.</p>
 *
 * @param phoneNumber 정규화된 11자리 휴대폰 번호 (^010\d{8}$)
 * @param channelCode 채널 식별 코드 (예: HOMEPAGE)
 * @param targetState 요청자가 원하는 변경 후 상태 (NONE / BASIC / PREMIUM)
 */
public record SubscriptionCommand(
        String phoneNumber,
        String channelCode,
        SubscriptionState targetState
) {
}
