package com.artinus.membership.subscription;

import com.artinus.membership.common.ChannelPolicyViolationException;
import com.artinus.membership.common.ResourceNotFoundException;
import com.artinus.membership.common.ConcurrentModificationException;
import com.artinus.membership.subscription.SubscriptionRequest;
import com.artinus.membership.subscription.SubscriptionResponse;
import com.artinus.membership.common.ExternalValidationRejectedException;
import com.artinus.membership.csrng.CsrngClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 구독/해지 유스케이스 오케스트레이터.
 *
 * <p><b>2-Phase TX 분리 구조</b> (handoff §3.1):
 * <ol>
 *   <li>{@link SubscriptionValidator#validate} — read-only 트랜잭션에서 도메인 정책 검증.</li>
 *   <li>{@link CsrngClient#fetchRandomBit} — 트랜잭션 <b>밖</b>에서 외부 API 호출.</li>
 *   <li>{@link SubscriptionApplier#apply} — 짧은 write 트랜잭션에서 상태 변경 + 이력 적재.</li>
 * </ol>
 * 본 오케스트레이터 메서드 자체에는 {@code @Transactional}을 부여하지 않는다.
 * 그 결과 csrng 호출이 DB 커넥션을 점유하지 않으며, random=0이면 write 단계에 진입조차 하지 않는다.</p>
 *
 * <p>self-invocation 함정 회피: validate/apply가 각각 별도 Spring Bean으로 분리되어 있어
 * AOP 프록시를 통해 호출되므로 @Transactional이 정상 적용된다.</p>
 *
 * <p>예외 매핑(handoff §3.2):
 * <ul>
 *   <li>Validator 단계 ResourceNotFoundException / ChannelPolicyViolationException /
 *       IllegalStateTransitionException → 그대로 위로 전파, Handler가 각각 404/422/422로 매핑.</li>
 *   <li>csrng random=0 → {@link ExternalValidationRejectedException} → 422.</li>
 *   <li>csrng 인프라 장애 → {@code CsrngException}/{@code HttpServerErrorException}/
 *       {@code ResourceAccessException} 그대로 전파 → 502.</li>
 *   <li>Applier 단계 ConcurrentModificationException → 409.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionValidator validator;
    private final SubscriptionApplier applier;
    private final CsrngClient csrngClient;
    private final Clock clock;

    public SubscriptionResponse execute(SubscriptionRequest request) {
        // 1단계 — 검증 (read-only TX, Bean 호출이므로 @Transactional AOP 적용됨).
        ValidationContext ctx = validator.validate(request);

        // 2단계 — 외부 API 호출 (TX 밖). Resilience4j 어노테이션은 CsrngClient에 부착됨.
        int randomBit = csrngClient.fetchRandomBit();
        if (randomBit == 0) {
            throw new ExternalValidationRejectedException(
                    "External validation rejected by csrng (random=0).");
        }

        // 3단계 — 적용 (write TX). occurredAt은 Clock 기반으로 결정성을 확보(테스트 용이).
        LocalDateTime occurredAt = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        return applier.apply(ctx, occurredAt);
    }
}
