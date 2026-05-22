package com.artinus.membership.subscription.domain;

import com.artinus.membership.common.exception.IllegalStateTransitionException;

import java.util.EnumMap;
import java.util.Map;

/**
 * 구독 상태 전이의 Single Source of Truth.
 * <pre>
 *   현재 \ 이벤트     SUBSCRIBE_BASIC  SUBSCRIBE_PREMIUM  UNSUBSCRIBE_BASIC  UNSUBSCRIBE_NONE
 *   NONE              BASIC            PREMIUM            거부               거부
 *   BASIC             거부(멱등)       PREMIUM            거부               NONE
 *   PREMIUM           거부             거부(멱등)         BASIC              NONE
 * </pre>
 * NONE→UNSUBSCRIBE 거부, 동일 상태 멱등 요청도 거부.
 */
public final class StateTransitionPolicy {

    private static final Map<SubscriptionState, Map<StateTransitionEvent, SubscriptionState>> TRANSITIONS;

    static {
        Map<SubscriptionState, Map<StateTransitionEvent, SubscriptionState>> table =
                new EnumMap<>(SubscriptionState.class);

        Map<StateTransitionEvent, SubscriptionState> fromNone = new EnumMap<>(StateTransitionEvent.class);
        fromNone.put(StateTransitionEvent.SUBSCRIBE_BASIC, SubscriptionState.BASIC);
        fromNone.put(StateTransitionEvent.SUBSCRIBE_PREMIUM, SubscriptionState.PREMIUM);
        table.put(SubscriptionState.NONE, fromNone);

        Map<StateTransitionEvent, SubscriptionState> fromBasic = new EnumMap<>(StateTransitionEvent.class);
        fromBasic.put(StateTransitionEvent.SUBSCRIBE_PREMIUM, SubscriptionState.PREMIUM);
        fromBasic.put(StateTransitionEvent.UNSUBSCRIBE_NONE, SubscriptionState.NONE);
        table.put(SubscriptionState.BASIC, fromBasic);

        Map<StateTransitionEvent, SubscriptionState> fromPremium = new EnumMap<>(StateTransitionEvent.class);
        fromPremium.put(StateTransitionEvent.UNSUBSCRIBE_BASIC, SubscriptionState.BASIC);
        fromPremium.put(StateTransitionEvent.UNSUBSCRIBE_NONE, SubscriptionState.NONE);
        table.put(SubscriptionState.PREMIUM, fromPremium);

        TRANSITIONS = table;
    }

    private StateTransitionPolicy() {
    }

    public static boolean canTransition(SubscriptionState current, StateTransitionEvent event) {
        requireNonNullArgs(current, event);
        Map<StateTransitionEvent, SubscriptionState> events = TRANSITIONS.get(current);
        return events != null && events.containsKey(event);
    }

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
