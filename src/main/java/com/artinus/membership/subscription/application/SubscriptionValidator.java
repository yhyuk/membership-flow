package com.artinus.membership.subscription.application;

import com.artinus.membership.channel.Channel;
import com.artinus.membership.channel.ChannelRepository;
import com.artinus.membership.common.exception.AlreadyInTargetStateException;
import com.artinus.membership.common.exception.ChannelPolicyViolationException;
import com.artinus.membership.common.exception.NoActiveSubscriptionException;
import com.artinus.membership.common.exception.ResourceNotFoundException;
import com.artinus.membership.member.Member;
import com.artinus.membership.member.MemberRepository;
import com.artinus.membership.subscription.domain.StateTransitionEvent;
import com.artinus.membership.subscription.domain.StateTransitionPolicy;
import com.artinus.membership.subscription.domain.Subscription;
import com.artinus.membership.subscription.domain.SubscriptionState;
import com.artinus.membership.subscription.dto.SubscriptionRequest;
import com.artinus.membership.subscription.persistence.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2-Phase TX의 1단계 — read-only 검증.
 * 채널 권한 → 상태 전이 순으로 검증해 사용자에게 의미 있는 에러를 우선 노출.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionValidator {

    private final MemberRepository memberRepository;
    private final ChannelRepository channelRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Transactional(readOnly = true)
    public ValidationContext validate(SubscriptionRequest request) {
        Channel channel = channelRepository.findByCode(request.channelCode())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", request.channelCode()));

        Member member = memberRepository.findByPhoneNumber(request.phoneNumber()).orElse(null);
        SubscriptionState currentState = (member == null)
                ? SubscriptionState.NONE
                : subscriptionRepository.findByMemberId(member.getId())
                        .map(Subscription::getState)
                        .orElse(SubscriptionState.NONE);
        SubscriptionState targetState = request.targetState();

        // 1) target=NONE은 항상 해지 의도 — current=NONE이면 "구독 중 아님" 메시지가 가장 명확.
        //    동일 상태 차단보다 우선해야 사용자가 "왜 해지가 안 되는지" 이해할 수 있다.
        StateTransitionEvent.ActionType intent = inferIntent(currentState, targetState);

        // 2) 동일 상태 재요청 차단 (NONE→NONE은 이미 1)에서 처리되었으므로 여기 도달 시 의미 있는 멱등).
        if (currentState == targetState) {
            throw new AlreadyInTargetStateException(currentState);
        }

        // 3) 채널 권한 사전 검증.
        ensureChannelAllowsAction(channel, intent);

        // 3) 상태 전이 매트릭스 검증.
        StateTransitionEvent event = resolveEvent(currentState, targetState, intent);
        StateTransitionPolicy.nextState(currentState, event);

        return new ValidationContext(member, channel, currentState, event, request.phoneNumber());
    }

    /**
     * (current, target) → 사용자의 의도(SUBSCRIBE / UNSUBSCRIBE) 추론.
     * <ul>
     *   <li>target == NONE → 항상 UNSUBSCRIBE</li>
     *   <li>current == NONE && target != NONE → SUBSCRIBE</li>
     *   <li>current == BASIC && target == PREMIUM → SUBSCRIBE (업그레이드)</li>
     *   <li>current == PREMIUM && target == BASIC → UNSUBSCRIBE (다운그레이드 = 부분 해지)</li>
     * </ul>
     */
    private static StateTransitionEvent.ActionType inferIntent(SubscriptionState current, SubscriptionState target) {
        if (target == SubscriptionState.NONE) {
            if (current == SubscriptionState.NONE) {
                throw new NoActiveSubscriptionException();
            }
            return StateTransitionEvent.ActionType.UNSUBSCRIBE;
        }
        if (current == SubscriptionState.PREMIUM && target == SubscriptionState.BASIC) {
            return StateTransitionEvent.ActionType.UNSUBSCRIBE;
        }
        return StateTransitionEvent.ActionType.SUBSCRIBE;
    }

    private static void ensureChannelAllowsAction(Channel channel, StateTransitionEvent.ActionType intent) {
        if (intent == StateTransitionEvent.ActionType.SUBSCRIBE && !channel.isSubscribable()) {
            throw new ChannelPolicyViolationException(
                    channel.getName() + " 채널에서는 구독을 할 수 없습니다.");
        }
        if (intent == StateTransitionEvent.ActionType.UNSUBSCRIBE && !channel.isUnsubscribable()) {
            throw new ChannelPolicyViolationException(
                    channel.getName() + " 채널에서는 구독 해지를 할 수 없습니다.");
        }
    }

    private static StateTransitionEvent resolveEvent(
            SubscriptionState current, SubscriptionState target, StateTransitionEvent.ActionType intent) {
        if (intent == StateTransitionEvent.ActionType.SUBSCRIBE) {
            return target == SubscriptionState.PREMIUM
                    ? StateTransitionEvent.SUBSCRIBE_PREMIUM
                    : StateTransitionEvent.SUBSCRIBE_BASIC;
        }
        // UNSUBSCRIBE
        return target == SubscriptionState.BASIC
                ? StateTransitionEvent.UNSUBSCRIBE_BASIC
                : StateTransitionEvent.UNSUBSCRIBE_NONE;
    }
}
