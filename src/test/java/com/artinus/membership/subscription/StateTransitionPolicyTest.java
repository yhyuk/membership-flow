package com.artinus.membership.subscription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StateTransitionPolicy} 18 케이스 TDD.
 *
 * <h2>케이스 분해</h2>
 * <p>ASSIGNMENT.md 매트릭스(line 71-90)에서 도출한 (현재 상태 × 이벤트) 조합과
 * handoff §1.3 / §3.5 모서리 케이스 5건을 합산하여 정확히 18 케이스를 정의한다.</p>
 *
 * <pre>
 * --- 허용 전이 (5건) ---
 *  1. NONE    + SUBSCRIBE_BASIC    → BASIC
 *  2. NONE    + SUBSCRIBE_PREMIUM  → PREMIUM
 *  3. BASIC   + SUBSCRIBE_PREMIUM  → PREMIUM
 *  4. BASIC   + UNSUBSCRIBE_NONE   → NONE
 *  5. PREMIUM + UNSUBSCRIBE_BASIC  → BASIC
 *  6. PREMIUM + UNSUBSCRIBE_NONE   → NONE
 *
 * --- 거부 전이 (7건) ---
 *  7. NONE    + UNSUBSCRIBE_BASIC  거부 (NONE→해지 불가, handoff §7)
 *  8. NONE    + UNSUBSCRIBE_NONE   거부 (멱등, handoff §7)
 *  9. BASIC   + SUBSCRIBE_BASIC    거부 (멱등, handoff §3.5)
 * 10. BASIC   + UNSUBSCRIBE_BASIC  거부 (BASIC→BASIC 의미 없음)
 * 11. PREMIUM + SUBSCRIBE_BASIC    거부 (다운그레이드는 UNSUBSCRIBE로만)
 * 12. PREMIUM + SUBSCRIBE_PREMIUM  거부 (멱등, handoff §3.5)
 *
 * --- 모서리 케이스 (6건) ---
 * 13. nextState() 거부 시 IllegalStateTransitionException 메시지에 current/event 포함
 * 14. canTransition 결과는 허용 전이에서 true
 * 15. canTransition 결과는 거부 전이에서 false
 * 16. nextState(null, event) → IllegalArgumentException
 * 17. nextState(state, null) → IllegalArgumentException
 * 18. canTransition(null, null) → IllegalArgumentException
 * </pre>
 */
class StateTransitionPolicyTest {

    // ---------------------------------------------------------------------
    // 허용 전이 6 케이스 (case 1~6)
    // ---------------------------------------------------------------------
    static Stream<Arguments> allowedTransitions() {
        return Stream.of(
                Arguments.of(SubscriptionState.NONE,    StateTransitionEvent.SUBSCRIBE_BASIC,    SubscriptionState.BASIC),
                Arguments.of(SubscriptionState.NONE,    StateTransitionEvent.SUBSCRIBE_PREMIUM,  SubscriptionState.PREMIUM),
                Arguments.of(SubscriptionState.BASIC,   StateTransitionEvent.SUBSCRIBE_PREMIUM,  SubscriptionState.PREMIUM),
                Arguments.of(SubscriptionState.BASIC,   StateTransitionEvent.UNSUBSCRIBE_NONE,   SubscriptionState.NONE),
                Arguments.of(SubscriptionState.PREMIUM, StateTransitionEvent.UNSUBSCRIBE_BASIC,  SubscriptionState.BASIC),
                Arguments.of(SubscriptionState.PREMIUM, StateTransitionEvent.UNSUBSCRIBE_NONE,   SubscriptionState.NONE)
        );
    }

    @DisplayName("[case 1-6] 허용 전이 — nextState가 정확한 다음 상태를 반환한다")
    @ParameterizedTest(name = "[{index}] {0} + {1} → {2}")
    @MethodSource("allowedTransitions")
    void nextState_allowedTransitions(SubscriptionState current,
                                      StateTransitionEvent event,
                                      SubscriptionState expected) {
        assertThat(StateTransitionPolicy.nextState(current, event)).isEqualTo(expected);
    }

    // ---------------------------------------------------------------------
    // 거부 전이 6 케이스 (case 7~12)
    // ---------------------------------------------------------------------
    static Stream<Arguments> rejectedTransitions() {
        return Stream.of(
                // NONE → UNSUBSCRIBE_* 모두 거부 (handoff §7)
                Arguments.of(SubscriptionState.NONE,    StateTransitionEvent.UNSUBSCRIBE_BASIC),
                Arguments.of(SubscriptionState.NONE,    StateTransitionEvent.UNSUBSCRIBE_NONE),
                // BASIC → SUBSCRIBE_BASIC (멱등) / UNSUBSCRIBE_BASIC (의미 없음)
                Arguments.of(SubscriptionState.BASIC,   StateTransitionEvent.SUBSCRIBE_BASIC),
                Arguments.of(SubscriptionState.BASIC,   StateTransitionEvent.UNSUBSCRIBE_BASIC),
                // PREMIUM → SUBSCRIBE_* 모두 거부 (PREMIUM은 가입/업그레이드 종착점)
                Arguments.of(SubscriptionState.PREMIUM, StateTransitionEvent.SUBSCRIBE_BASIC),
                Arguments.of(SubscriptionState.PREMIUM, StateTransitionEvent.SUBSCRIBE_PREMIUM)
        );
    }

    @DisplayName("[case 7-12] 거부 전이 — nextState는 IllegalStateTransitionException")
    @ParameterizedTest(name = "[{index}] {0} + {1} 거부")
    @MethodSource("rejectedTransitions")
    void nextState_rejectedTransitions(SubscriptionState current, StateTransitionEvent event) {
        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> StateTransitionPolicy.nextState(current, event))
                .satisfies(ex -> {
                    assertThat(ex.currentState()).isEqualTo(current);
                    assertThat(ex.event()).isEqualTo(event);
                });
    }

    // ---------------------------------------------------------------------
    // 모서리 케이스 6건 (case 13~18)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("모서리 케이스")
    class EdgeCases {

        @Test
        @DisplayName("[case 13] 거부 시 예외 메시지에 current state와 event 정보를 포함한다")
        void rejected_messageIncludesContext() {
            // NONE → UNSUBSCRIBE_NONE 은 handoff §7 결정에 따라 거부되어야 한다.
            assertThatThrownBy(() ->
                    StateTransitionPolicy.nextState(SubscriptionState.NONE, StateTransitionEvent.UNSUBSCRIBE_NONE))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("NONE")
                    .hasMessageContaining("UNSUBSCRIBE_NONE");
        }

        @Test
        @DisplayName("[case 14] canTransition은 허용 전이에서 true를 반환한다")
        void canTransition_trueForAllowed() {
            assertThat(StateTransitionPolicy.canTransition(
                    SubscriptionState.NONE, StateTransitionEvent.SUBSCRIBE_BASIC)).isTrue();
            assertThat(StateTransitionPolicy.canTransition(
                    SubscriptionState.PREMIUM, StateTransitionEvent.UNSUBSCRIBE_NONE)).isTrue();
        }

        @Test
        @DisplayName("[case 15] canTransition은 거부 전이에서 false를 반환한다")
        void canTransition_falseForRejected() {
            // NONE → UNSUBSCRIBE (handoff §7), BASIC → SUBSCRIBE_BASIC (멱등)
            assertThat(StateTransitionPolicy.canTransition(
                    SubscriptionState.NONE, StateTransitionEvent.UNSUBSCRIBE_NONE)).isFalse();
            assertThat(StateTransitionPolicy.canTransition(
                    SubscriptionState.BASIC, StateTransitionEvent.SUBSCRIBE_BASIC)).isFalse();
            assertThat(StateTransitionPolicy.canTransition(
                    SubscriptionState.PREMIUM, StateTransitionEvent.SUBSCRIBE_PREMIUM)).isFalse();
        }

        @Test
        @DisplayName("[case 16] nextState(null, event)는 IllegalArgumentException (NPE 아님)")
        void nextState_nullCurrent_throws() {
            assertThatThrownBy(() ->
                    StateTransitionPolicy.nextState(null, StateTransitionEvent.SUBSCRIBE_BASIC))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("current");
        }

        @Test
        @DisplayName("[case 17] nextState(state, null)는 IllegalArgumentException (NPE 아님)")
        void nextState_nullEvent_throws() {
            assertThatThrownBy(() ->
                    StateTransitionPolicy.nextState(SubscriptionState.BASIC, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("event");
        }

        @Test
        @DisplayName("[case 18] canTransition(null, null)는 IllegalArgumentException (NPE 아님)")
        void canTransition_bothNull_throws() {
            assertThatThrownBy(() -> StateTransitionPolicy.canTransition(null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
