package com.artinus.subscription.application.exception;

/**
 * 채널 권한 정책(subscribable / unsubscribable)을 위반한 요청.
 *
 * <p>예: CALL_CENTER 채널({@code subscribable=false})에 SUBSCRIBE 요청.
 * handoff §3.2 매핑: 422 {@code CHANNEL_POLICY_VIOLATION}.</p>
 */
public class ChannelPolicyViolationException extends RuntimeException {

    public ChannelPolicyViolationException(String message) {
        super(message);
    }
}
