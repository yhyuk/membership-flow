package com.artinus.membership.subscription.domain;

/** 사용자 응답 메시지용 한글 라벨. */
public final class SubscriptionStateLabel {

    private SubscriptionStateLabel() {
    }

    public static String of(SubscriptionState state) {
        if (state == null) {
            return "알 수 없음";
        }
        return switch (state) {
            case NONE -> "구독 안 함";
            case BASIC -> "일반 구독";
            case PREMIUM -> "프리미엄 구독";
        };
    }
}
