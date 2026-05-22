package com.artinus.membership.subscription;

import com.artinus.membership.channel.Channel;
import com.artinus.membership.member.Member;
import com.artinus.membership.subscription.StateTransitionEvent;
import com.artinus.membership.subscription.SubscriptionState;

/**
 * 1단계(read-only validate)에서 산출되어 3단계(write apply)에 전달되는 검증 컨텍스트.
 *
 * <p>외부 API(csrng) 호출은 본 컨텍스트 생성 이후, write 트랜잭션 시작 이전에 수행된다(2-Phase TX).</p>
 *
 * @param member         조회된 회원 (신규 회원이면 {@code null} — Applier가 INSERT)
 * @param channel        조회된 채널 (시드 데이터에서 발견되어야 함)
 * @param currentState   현재 구독 상태 (구독 행 미존재 또는 신규 회원이면 {@link SubscriptionState#NONE})
 * @param event          처리할 전이 이벤트
 * @param normalizedPhoneNumber 정규화된 11자리 휴대폰 번호 (신규 회원 INSERT에 사용)
 */
record ValidationContext(
        Member member,
        Channel channel,
        SubscriptionState currentState,
        StateTransitionEvent event,
        String normalizedPhoneNumber
) {
}
