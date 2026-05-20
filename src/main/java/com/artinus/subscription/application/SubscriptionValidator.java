package com.artinus.subscription.application;

import com.artinus.subscription.application.exception.ChannelPolicyViolationException;
import com.artinus.subscription.application.exception.ResourceNotFoundException;
import com.artinus.subscription.domain.Channel;
import com.artinus.subscription.domain.IllegalStateTransitionException;
import com.artinus.subscription.domain.Member;
import com.artinus.subscription.domain.StateTransitionEvent;
import com.artinus.subscription.domain.StateTransitionPolicy;
import com.artinus.subscription.domain.Subscription;
import com.artinus.subscription.domain.SubscriptionState;
import com.artinus.subscription.infrastructure.repository.ChannelRepository;
import com.artinus.subscription.infrastructure.repository.MemberRepository;
import com.artinus.subscription.infrastructure.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 2-Phase TX의 1단계 — read-only 검증.
 *
 * <p>자체 트랜잭션({@code readOnly=true})에서 회원/채널/구독 행을 조회하고
 * 채널 권한과 상태 전이 정책을 검증한다. 외부 API(csrng)는 호출하지 않는다.</p>
 *
 * <p>액션 결정: 현재 상태({@code currentState})와 요청 {@code targetState}를 비교하여
 * (action, targetState) 4-tuple에 해당하는 {@link StateTransitionEvent}로 변환한다.
 * 이를 통해 컨트롤러는 단일 엔드포인트로 구독/해지를 모두 처리할 수 있다.</p>
 *
 * <p>별도 Spring Bean으로 분리한 이유: 같은 클래스의 {@code @Transactional} 메서드 self-invocation
 * 함정을 회피하기 위함이다(SubscriptionService가 본 Bean을 주입받아 호출하면 AOP 프록시 적용됨).</p>
 *
 * <p>핵심 결정:
 * <ul>
 *   <li>신규 회원(phoneNumber 미존재) — 본 단계에서는 {@link Member}=null로 두고 통과시킨다.
 *       회원 생성은 write 트랜잭션(Applier)에서 수행하여 동시 INSERT 충돌을 UNIQUE 제약으로 감지한다.</li>
 *   <li>구독 행 미존재 — currentState={@link SubscriptionState#NONE}으로 간주.</li>
 *   <li>상태 전이 검증은 {@link StateTransitionPolicy}에 위임 — Single Source of Truth.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SubscriptionValidator {

    private final MemberRepository memberRepository;
    private final ChannelRepository channelRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Transactional(readOnly = true)
    public ValidationContext validate(SubscriptionCommand cmd) {
        Channel channel = channelRepository.findByCode(cmd.channelCode())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", cmd.channelCode()));

        Optional<Member> memberOpt = memberRepository.findByPhoneNumber(cmd.phoneNumber());
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

        // 현재 상태 + 목표 상태로 event를 결정. 매트릭스에 없는 조합이면 IllegalStateTransitionException.
        StateTransitionEvent event = resolveEvent(currentState, cmd.targetState());

        // 채널 권한 검증 — 결정된 액션이 채널이 허용하는지 확인.
        ensureChannelAllowsEvent(channel, event);

        // 상태 전이 정책 위반 시 IllegalStateTransitionException — 422로 매핑됨.
        // resolveEvent가 이미 거부했을 가능성이 높지만 SoT 검증 통과를 명시.
        StateTransitionPolicy.nextState(currentState, event);

        return new ValidationContext(member, channel, currentState, event, cmd.phoneNumber());
    }

    /**
     * (현재 상태, 목표 상태) 조합으로부터 {@link StateTransitionEvent}를 산출한다.
     *
     * <p>매트릭스(handoff §3.2 / StateTransitionPolicy):
     * <pre>
     *   현재 → 목표      Event
     *   NONE → BASIC     SUBSCRIBE_BASIC
     *   NONE → PREMIUM   SUBSCRIBE_PREMIUM
     *   BASIC → PREMIUM  SUBSCRIBE_PREMIUM
     *   BASIC → NONE     UNSUBSCRIBE_NONE
     *   PREMIUM → BASIC  UNSUBSCRIBE_BASIC
     *   PREMIUM → NONE   UNSUBSCRIBE_NONE
     *   그 외(동일 상태 / 금지 전이) → 거부
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
