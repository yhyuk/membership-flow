package com.artinus.subscription.external.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Gemini(LLM) 전용 {@link RestClient} 빈 구성.
 *
 * <p>csrng와 동일한 패턴(handoff §3.3 C-1)을 따른다. 동기 RestClient의 자체 timeout을
 * {@link ClientHttpRequestFactorySettings}로 명시하고 {@code @TimeLimiter}는 사용하지 않는다.</p>
 *
 * <p>전역 RestClient 빈이 아닌 {@code geminiRestClient} named bean으로만 노출하여
 * csrng RestClient와 인스턴스를 분리한다. 두 외부 API는 SLA·장애 특성·timeout 정책이 서로 다르므로
 * RestClient 인스턴스를 공유하지 않는다.</p>
 */
@Configuration
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiClientConfig {

    /**
     * Gemini 호출 전용 RestClient. base URL + Accept/Content-Type 헤더(JSON) + timeout 적용.
     *
     * <p>Resilience4j와 무관한 순수 HTTP 클라이언트 책임만 가진다.
     * Retry/CircuitBreaker는 {@link GeminiClient}의 메서드에 어노테이션으로 부착된다.</p>
     */
    @Bean
    public RestClient geminiRestClient(GeminiProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }
}
