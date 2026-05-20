package com.artinus.subscription.domain;

/**
 * 허용되지 않은 상태 전이 요청에 대한 도메인 예외.
 *
 * <p>{@link StateTransitionPolicy#nextState(SubscriptionState, StateTransitionEvent)} 호출 시
 * 전이가 정의되어 있지 않으면 발생한다. 호출자는 메시지에서 현재 상태와
 * 이벤트 정보를 그대로 얻을 수 있다.</p>
 *
 * <p>이 예외는 도메인 규칙 위반을 의미하므로, 상위 계층에서
 * HTTP 422 {@code INVALID_STATE_TRANSITION}으로 매핑된다(handoff §3.2).</p>
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final SubscriptionState currentState;
    private final StateTransitionEvent event;

    public IllegalStateTransitionException(SubscriptionState currentState, StateTransitionEvent event) {
        super(buildMessage(currentState, event));
        this.currentState = currentState;
        this.event = event;
    }

    public SubscriptionState currentState() {
        return currentState;
    }

    public StateTransitionEvent event() {
        return event;
    }

    private static String buildMessage(SubscriptionState currentState, StateTransitionEvent event) {
        return "Illegal state transition: current=" + currentState + ", event=" + event;
    }
}
