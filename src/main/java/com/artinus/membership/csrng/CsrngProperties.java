package com.artinus.membership.csrng;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** external.csrng.* 프로퍼티 — base URL과 connect/read timeout(ms). */
@ConfigurationProperties(prefix = "external.csrng")
public record CsrngProperties(
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs
) {
}
