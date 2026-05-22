package com.artinus.membership.common.exception;

/** 채널 권한 정책(subscribable/unsubscribable) 위반. 422 CHANNEL_POLICY_VIOLATION. */
public class ChannelPolicyViolationException extends RuntimeException {

    public ChannelPolicyViolationException(String message) {
        super(message);
    }
}
