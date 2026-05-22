package com.artinus.membership.subscription.application;

import com.artinus.membership.channel.Channel;
import com.artinus.membership.member.Member;
import com.artinus.membership.subscription.domain.StateTransitionEvent;
import com.artinus.membership.subscription.domain.SubscriptionState;

/** Validator → Applier 사이에 전달되는 검증 컨텍스트. 신규 회원이면 member=null. */
record ValidationContext(
        Member member,
        Channel channel,
        SubscriptionState currentState,
        StateTransitionEvent event,
        String normalizedPhoneNumber
) {
}
