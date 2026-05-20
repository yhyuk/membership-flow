package com.artinus.subscription.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * 구독 상태 전이의 단일 진실 공급원 (Single Source of Truth).
 *
 * <p>ASSIGNMENT.md (line 71-90)의 전이 매트릭스를 그대로 옮긴 immutable 룩업 테이블이다.
 * Spring Bean으로 등록하지 않으며 (불필요한 컨테이너 의존 회피), 정적 메서드만 노출한다.</p>
 *
 * <h2>전이 매트릭스</h2>
 * <pre>
 *   현재 \ 이벤트     SUBSCRIBE_BASIC  SUBSCRIBE_PREMIUM  UNSUBSCRIBE_BASIC  UNSUBSCRIBE_NONE
 *   NONE              BASIC            PREMIUM            거부               거부
 *   BASIC             거부(멱등)       PREMIUM            거부               NONE
 *   PREMIUM           거부             거부(멱등)         BASIC              NONE
 * </pre>
 *
 * <h2>핵심 결정</h2>
 * <ul>
 *   <li><b>NONE → UNSUBSCRIBE 거부</b> — handoff §7 해석 2 채택.
 *       "이미 구독하지 않은 상태"는 해지 불가.</li>
 *   <li><b>동일 상태로의 멱등 요청 거부</b> — BASIC→SUBSCRIBE_BASIC 등은
 *       의미 없는 요청이므로 매트릭스에서 제외 (handoff §3.5).</li>
 *   <li><b>null 방어</b> — 어느 인자라도 null이면 NPE가 아닌
 *       {@link IllegalArgumentException}으로 명시적 거부.</li>
 * </ul>
 */
public final class StateTransitionPolicy {

    private static final Map<SubscriptionState, Map<StateTransitionEvent, SubscriptionState>> TRANSITIONS;

    static {
        Map<SubscriptionState, Map<StateTransitionEvent, SubscriptionState>> table =
                new EnumMap<>(SubscriptionState.class);

        // NONE 에서의 전이 — 신규 가입만 가능
        Map<StateTransitionEvent, SubscriptionState> fromNone = new EnumMap<>(StateTransitionEvent.class);
        fromNone.put(StateTransitionEvent.SUBSCRIBE_BASIC, SubscriptionState.BASIC);
        fromNone.put(StateTransitionEvent.SUBSCRIBE_PREMIUM, SubscriptionState.PREMIUM);
        // UNSUBSCRIBE_*는 정의하지 않음 (NONE→UNSUBSCRIBE 거부, handoff §7)
        table.put(SubscriptionState.NONE, fromNone);

        // BASIC 에서의 전이 — 프리미엄 업그레이드 또는 NONE 해지
        Map<StateTransitionEvent, SubscriptionState> fromBasic = new EnumMap<>(StateTransitionEvent.class);
        fromBasic.put(StateTransitionEvent.SUBSCRIBE_PREMIUM, SubscriptionState.PREMIUM);
        fromBasic.put(StateTransitionEvent.UNSUBSCRIBE_NONE, SubscriptionState.NONE);
        // SUBSCRIBE_BASIC (멱등) 및 UNSUBSCRIBE_BASIC (BASIC→BASIC) 모두 거부
        table.put(SubscriptionState.BASIC, fromBasic);

        // PREMIUM 에서의 전이 — 일반 다운그레이드 또는 NONE 해지
        Map<StateTransitionEvent, SubscriptionState> fromPremium = new EnumMap<>(StateTransitionEvent.class);
        fromPremium.put(StateTransitionEvent.UNSUBSCRIBE_BASIC, SubscriptionState.BASIC);
        fromPremium.put(StateTransitionEvent.UNSUBSCRIBE_NONE, SubscriptionState.NONE);
        // SUBSCRIBE_* 모두 거부 (PREMIUM은 가입/업그레이드 종착점)
        table.put(SubscriptionState.PREMIUM, fromPremium);

        // 내부 Map은 EnumMap 그대로 사용 (외부 노출 안 함, 정적 메서드로만 접근)
        TRANSITIONS = table;
    }

    private StateTransitionPolicy() {
        // 인스턴스화 방지
    }

    /**
     * 주어진 (현재 상태, 이벤트) 조합이 허용되는 전이인지 검사.
     *
     * @return 정의된 전이면 {@code true}, 그렇지 않으면 {@code false}
     * @throws IllegalArgumentException 인자가 null이면 발생
     */
    public static boolean canTransition(SubscriptionState current, StateTransitionEvent event) {
        requireNonNullArgs(current, event);
        Map<StateTransitionEvent, SubscriptionState> events = TRANSITIONS.get(current);
        return events != null && events.containsKey(event);
    }

    /**
     * 주어진 (현재 상태, 이벤트) 조합의 다음 상태를 산출한다.
     *
     * @return 전이 후 상태
     * @throws IllegalArgumentException 인자가 null이면 발생
     * @throws IllegalStateTransitionException 전이가 매트릭스에 정의되어 있지 않으면 발생
     */
    public static SubscriptionState nextState(SubscriptionState current, StateTransitionEvent event) {
        requireNonNullArgs(current, event);
        Map<StateTransitionEvent, SubscriptionState> events = TRANSITIONS.get(current);
        SubscriptionState next = (events == null) ? null : events.get(event);
        if (next == null) {
            throw new IllegalStateTransitionException(current, event);
        }
        return next;
    }

    private static void requireNonNullArgs(SubscriptionState current, StateTransitionEvent event) {
        if (current == null) {
            throw new IllegalArgumentException("current state must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
    }
}
