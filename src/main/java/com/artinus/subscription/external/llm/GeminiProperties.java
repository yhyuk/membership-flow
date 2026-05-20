package com.artinus.subscription.external.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini(LLM) 외부 API 클라이언트 설정.
 *
 * <p>base-url은 Google Generative Language API 공식 엔드포인트
 * ({@code https://generativelanguage.googleapis.com})를 기본값으로 한다.
 * timeout은 csrng보다 응답이 느린 점을 반영해 connect 2초 / read 10초.
 * Retry/CircuitBreaker는 application.yml의 {@code resilience4j.*} "gemini" 인스턴스가 담당하며
 * 본 프로퍼티는 HTTP 클라이언트 단의 저수준 timeout과 모델·키 등 외부화 가능한 메타만 보유한다.</p>
 *
 * <p>apiKey는 환경변수 {@code GEMINI_API_KEY}에서 주입한다(handoff §3.6 PII/토큰 보호 별개의 secret 관리).
 * 빈 문자열을 디폴트로 두어 평가자/CI 환경에서도 부팅이 실패하지 않도록 한다. 빈 키 상태에서 호출이 발생하면
 * {@link GeminiClient}가 즉시 {@link GeminiException}으로 fail-fast하며 HTTP 호출을 시도하지 않는다.</p>
 *
 * @param baseUrl             Google Generative Language API 엔드포인트
 * @param model               사용 모델 ID (예: gemini-2.0-flash)
 * @param apiKey              API Key (환경변수 주입). 빈 문자열이면 호출이 즉시 실패한다.
 * @param connectTimeoutMs    TCP connect 타임아웃 (ms)
 * @param readTimeoutMs       응답 read 타임아웃 (ms)
 * @param maxRecentHistories  프롬프트에 포함할 최근 이력 개수 (handoff §3.6 — 토큰 절감 및 요약 집중도)
 */
@ConfigurationProperties(prefix = "external.llm.gemini")
public record GeminiProperties(
        String baseUrl,
        String model,
        String apiKey,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxRecentHistories
) {
}
