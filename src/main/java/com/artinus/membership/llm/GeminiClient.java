package com.artinus.membership.llm;

import com.artinus.membership.history.domain.SubscriptionHistory;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Gemini(LLM) 어댑터. 모든 실패는 GeminiException 또는 5xx/IO 예외로 전파 (fallback 없음). */
@Component
public class GeminiClient {

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/{model}:generateContent?key={apiKey}";
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
     * 이력을 자연어로 요약.
     *
     * @throws GeminiException                                                api-key 미설정 / 4xx / 안전 필터 차단 / 빈 응답
     * @throws org.springframework.web.client.HttpServerErrorException        5xx (재시도 소진 후 전파)
     * @throws org.springframework.web.client.ResourceAccessException         IO/timeout (재시도 소진 후 전파)
     */
    @Retry(name = "gemini")
    @CircuitBreaker(name = "gemini")
    public String summarize(List<SubscriptionHistory> recent, Map<Long, String> channelNameById) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new GeminiException("api-key not configured");
        }

        GeminiRequest body = PromptTemplate.buildRequest(recent, channelNameById);

        GeminiResponse response = geminiRestClient.post()
                .uri(GENERATE_CONTENT_PATH, properties.model(), properties.apiKey())
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw new GeminiException(
                            "client error: status=" + resp.getStatusCode().value());
                })
                .body(GeminiResponse.class);

        return extractText(response);
    }

    private static String extractText(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new GeminiException("empty candidates");
        }
        GeminiResponse.Candidate first = response.candidates().get(0);
        if (first == null) {
            throw new GeminiException("empty first candidate");
        }
        // Gemini는 안전 필터에 걸리면 200 + finishReason="SAFETY"로 응답한다.
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
