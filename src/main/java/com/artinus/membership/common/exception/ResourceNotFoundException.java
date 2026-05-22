package com.artinus.membership.common.exception;

/** 회원/채널 미존재. 404 RESOURCE_NOT_FOUND로 매핑. */
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
