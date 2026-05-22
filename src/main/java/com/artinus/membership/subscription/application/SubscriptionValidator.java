package com.artinus.membership.subscription.application;

import com.artinus.membership.channel.Channel;
import com.artinus.membership.channel.ChannelRepository;
import com.artinus.membership.common.exception.ChannelPolicyViolationException;
import com.artinus.membership.common.exception.IllegalStateTransitionException;
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

import java.util.Optional;

/**
 * 2-Phase TX의 1단계 — read-only 검증.
 * Service와 별도 빈으로 둔 이유: {@code @Transactional} self-invocation 회피.
 *
 * <p>신규 회원은 null로 통과시키고 INSERT는 Applier에 위임(UNIQUE 충돌로 동시 가입 감지).
 * 구독 행이 없으면 currentState=NONE으로 간주.
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

        Optional<Member> memberOpt = memberRepository.findByPhoneNumber(request.phoneNumber());
        Member member = memberOpt.orElse(null);

        SubscriptionState currentState;
        if (member == null) {
            currentState = SubscriptionState.NONE;
        } else {
            currentState = subscriptionRepository
                    .findByMemberIdAndChannelId(member.getId(), channel.getId())
                    .map(Subscription::getState)
                    .orElse(SubscriptionState.NONE);
        }

        StateTransitionEvent event = resolveEvent(currentState, request.targetState());
        ensureChannelAllowsEvent(channel, event);
        StateTransitionPolicy.nextState(currentState, event);

        return new ValidationContext(member, channel, currentState, event, request.phoneNumber());
    }

    /**
     * (current, target) → StateTransitionEvent 매핑. 매트릭스에 없는 조합(동일 상태 등)은 거부.
     * <pre>
     *   NONE → BASIC/PREMIUM    SUBSCRIBE_*
     *   BASIC → PREMIUM         SUBSCRIBE_PREMIUM
     *   BASIC → NONE            UNSUBSCRIBE_NONE
     *   PREMIUM → BASIC/NONE    UNSUBSCRIBE_*
     * </pre>
     */
    private static StateTransitionEvent resolveEvent(SubscriptionState current, SubscriptionState target) {
        if (current == null || target == null) {
            throw new IllegalArgumentException("currentState and targetState must not be null");
        }
        return switch (current) {
            case NONE -> switch (target) {
                case BASIC -> StateTransitionEvent.SUBSCRIBE_BASIC;
                case PREMIUM -> StateTransitionEvent.SUBSCRIBE_PREMIUM;
                case NONE -> throw new IllegalStateTransitionException(current, null);
            };
            case BASIC -> switch (target) {
                case PREMIUM -> StateTransitionEvent.SUBSCRIBE_PREMIUM;
                case NONE -> StateTransitionEvent.UNSUBSCRIBE_NONE;
                case BASIC -> throw new IllegalStateTransitionException(current, null);
            };
            case PREMIUM -> switch (target) {
                case BASIC -> StateTransitionEvent.UNSUBSCRIBE_BASIC;
                case NONE -> StateTransitionEvent.UNSUBSCRIBE_NONE;
                case PREMIUM -> throw new IllegalStateTransitionException(current, null);
            };
        };
    }

    private static void ensureChannelAllowsEvent(Channel channel, StateTransitionEvent event) {
        if (event.action() == StateTransitionEvent.ActionType.SUBSCRIBE && !channel.isSubscribable()) {
            throw new ChannelPolicyViolationException(
                    "Channel '" + channel.getCode() + "' does not allow SUBSCRIBE");
        }
        if (event.action() == StateTransitionEvent.ActionType.UNSUBSCRIBE && !channel.isUnsubscribable()) {
            throw new ChannelPolicyViolationException(
                    "Channel '" + channel.getCode() + "' does not allow UNSUBSCRIBE");
        }
    }
}
