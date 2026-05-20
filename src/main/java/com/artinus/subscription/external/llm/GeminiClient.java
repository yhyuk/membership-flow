package com.artinus.subscription.external.llm;

import com.artinus.subscription.domain.SubscriptionHistory;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Gemini(LLM) 외부 API 어댑터.
 *
 * <p>책임: HTTP 호출 + 응답 매핑 + 실패 분류만 담당한다. 비즈니스 응답 degrade(status=DEGRADED)는
 * 상위 서비스({@code HistoryService})가 본 어댑터의 예외를 catch하여 처리한다 (어댑터에 fallback 없음).
 *
 * <p>분류:
 * <ul>
 *   <li>2xx + 정상 candidates[0].content.parts[0].text → 평문 반환</li>
 *   <li>2xx + 빈 candidates / 빈 text → {@link GeminiException}("empty ...")</li>
 *   <li>2xx + finishReason ∈ {"SAFETY", "BLOCKED"} → {@link GeminiException}("blocked by safety filter")</li>
 *   <li>4xx → 즉시 {@link GeminiException}("client error: ...") — 재시도 무력 (ignore-exceptions)</li>
 *   <li>5xx → {@link org.springframework.web.client.HttpServerErrorException} → application.yml retry-exceptions에 의해 재시도, 최종 실패 시 호출자에 전파</li>
 *   <li>IO/timeout → {@link org.springframework.web.client.ResourceAccessException} → 재시도</li>
 *   <li>apiKey 빈 문자열 → HTTP 호출 시도 없이 즉시 {@link GeminiException}("api-key not configured")</li>
 * </ul>
 *
 * <p>Resilience4j:
 * <ul>
 *   <li>{@code @Retry(name = "gemini")} — 5xx 및 IO 실패 시 application.yml 설정대로 재시도(max-attempts=2)</li>
 *   <li>{@code @CircuitBreaker(name = "gemini")} — 누적 실패율 임계 초과 시 OPEN 진입</li>
 * </ul>
 *
 * <p>Fallback 메서드는 작성하지 않는다 (어댑터 fail-closed, handoff 결정).</p>
 */
@Component
public class GeminiClient {

    /** Google Generative Language API의 generateContent 경로 템플릿. */
    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/{model}:generateContent?key={apiKey}";

    /** 안전 필터에 의한 차단 표시 finishReason 값. */
    private static final String FINISH_SAFETY = "SAFETY";
    private static final String FINISH_BLOCKED = "BLOCKED";

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;

    public GeminiClient(
            @Qualifier("geminiRestClient") RestClient geminiRestClient,
            GeminiProperties properties) {
        this.geminiRestClient = geminiRestClient;
        this.properties = properties;
    }

    /**
     * 이력 목록을 자연어로 요약한다.
     *
     * <p>본 메서드는 비즈니스 의사결정을 하지 않는다. 호출자({@code HistoryService})가 본 메서드의 결과를
     * 응답의 {@code summary} 필드에, 예외를 {@code status=DEGRADED}로 매핑한다.</p>
     *
     * <p><b>API Key 미설정 우선 체크</b>: properties.apiKey()가 null/blank이면 HTTP 호출을 시도하지 않고
     * 즉시 {@link GeminiException}을 던진다. application.yml의 ignore-exceptions에 등록되어 있어
     * Retry가 가로채지 않는다. WireMock 검증 시 호출 0회를 기대 가능.</p>
     *
     * @param recent          최근 N건 이력 (정확한 N 제한은 호출자 책임 — 본 메서드는 받은 만큼 그대로 사용)
     * @param channelCodeById channelId → channelCode 매핑 (PII 미포함 채널 코드만 프롬프트에 들어감)
     * @return 요약 평문
     * @throws GeminiException                                              4xx, 응답 차단, 빈 응답, API Key 미설정 등
     * @throws org.springframework.web.client.HttpServerErrorException      5xx (Retry 모두 소진 후 호출자 전파)
     * @throws org.springframework.web.client.ResourceAccessException       IO/timeout (Retry 모두 소진 후 호출자 전파)
     */
    @Retry(name = "gemini")
    @CircuitBreaker(name = "gemini")
    public String summarize(List<SubscriptionHistory> recent, Map<Long, String> channelCodeById) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            // 부팅은 성공시키되 호출은 fail-fast. ignore-exceptions로 Retry/CB 카운트도 회피한다.
            throw new GeminiException("api-key not configured");
        }

        GeminiRequest body = PromptTemplate.buildRequest(recent, channelCodeById);

        GeminiResponse response = geminiRestClient.post()
                .uri(GENERATE_CONTENT_PATH, properties.model(), properties.apiKey())
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    // 4xx는 호출 측 잘못이므로 재시도 무의미 → 즉시 GeminiException.
                    // ignore-exceptions에 등록되어 있어 Retry가 가로채지 않는다.
                    throw new GeminiException(
                            "client error: status=" + resp.getStatusCode().value());
                })
                // 5xx는 onStatus로 가로채지 않는다 — HttpServerErrorException으로 전파되어
                // application.yml retry-exceptions에 의해 재시도된다.
                .body(GeminiResponse.class);

        return extractText(response);
    }

    /**
     * 응답에서 안전 필터 차단/빈 candidates/빈 text를 확인하고 최종 평문을 추출한다.
     *
     * @throws GeminiException 차단/빈 응답
     */
    private static String extractText(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new GeminiException("empty candidates");
        }
        GeminiResponse.Candidate first = response.candidates().get(0);
        if (first == null) {
            throw new GeminiException("empty first candidate");
        }
        // 안전 필터/BLOCKED 차단 — Gemini는 200 OK + finishReason="SAFETY"로 반환한다.
        String finishReason = first.finishReason();
        if (FINISH_SAFETY.equals(finishReason) || FINISH_BLOCKED.equals(finishReason)) {
            throw new GeminiException("blocked by safety filter: finishReason=" + finishReason);
        }
        GeminiResponse.Content content = first.content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            throw new GeminiException("empty content parts");
        }
        String text = content.parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw new GeminiException("empty text");
        }
        return text;
    }
}
