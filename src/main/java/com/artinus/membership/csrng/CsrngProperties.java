package com.artinus.membership.csrng;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * csrng 외부 API 클라이언트 설정.
 *
 * <p>base-url은 ASSIGNMENT.md(§외부 API) 명시 엔드포인트(https://csrng.net/csrng/csrng.php)를 기본값으로 한다.
 * 타임아웃은 handoff §3.3 권장값(connect 1초, read 2초)을 사용한다.
 * Retry/CircuitBreaker는 application.yml의 resilience4j.* 인스턴스 "csrng"가 담당하며
 * 본 프로퍼티는 HTTP 클라이언트 단의 저수준 timeout만 외부화한다.
 *
 * @param baseUrl csrng 엔드포인트 (쿼리스트링 제외, GET 호출 시 동적 결합)
 * @param connectTimeoutMs TCP connect 타임아웃 (ms)
 * @param readTimeoutMs 응답 read 타임아웃 (ms)
 */
@ConfigurationProperties(prefix = "external.csrng")
public record CsrngProperties(
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs
) {
}
