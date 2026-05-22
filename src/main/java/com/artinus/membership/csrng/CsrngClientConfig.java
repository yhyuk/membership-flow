package com.artinus.membership.csrng;

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
 * csrng 전용 {@link RestClient} 빈 구성.
 *
 * <p>handoff §3.3 C-1 결정에 따라 동기 RestClient의 자체 timeout(connect/read)을
 * {@link ClientHttpRequestFactorySettings}로 명시한다. {@code @TimeLimiter} 미사용.
 *
 * <p>전역 RestClient 빈이 아닌 {@code csrngRestClient}라는 named bean으로만 노출하여
 * 다른 외부 어댑터(LLM 등)와 인스턴스를 분리한다.
 */
@Configuration
@EnableConfigurationProperties(CsrngProperties.class)
public class CsrngClientConfig {

    /**
     * csrng 호출 전용 RestClient. base URL + Accept(JSON) 헤더 + timeout 적용.
     *
     * <p>aspect 적용 순서는 application.yml의 resilience4j.* aspect order에 따르며
     * 본 빈은 Resilience4j와 무관한 순수 HTTP 클라이언트 책임만 가진다.
     */
    @Bean
    public RestClient csrngRestClient(CsrngProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }
}
