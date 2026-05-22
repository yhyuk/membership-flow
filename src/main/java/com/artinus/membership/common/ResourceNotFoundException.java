package com.artinus.membership.common;

/**
 * 회원 또는 채널이 존재하지 않을 때 발생.
 *
 * <p>handoff §3.2 HTTP 매트릭스 매핑: 404 {@code RESOURCE_NOT_FOUND}.</p>
 *
 * <p>resourceType/identifier 2개 슬롯으로 단일화하여 회원/채널 미존재를 통합 표현한다.
 * 별도 MemberNotFoundException/ChannelNotFoundException 분리는 오버엔지니어링으로 회피.</p>
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;
    private final String identifier;

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(buildMessage(resourceType, identifier));
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    public String resourceType() {
        return resourceType;
    }

    public String identifier() {
        return identifier;
    }

    private static String buildMessage(String resourceType, String identifier) {
        return resourceType + " not found: " + identifier;
    }
}
