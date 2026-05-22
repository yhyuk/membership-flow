package com.artinus.membership.subscription.application;

import com.artinus.membership.common.exception.ExternalValidationRejectedException;
import com.artinus.membership.csrng.CsrngClient;
import com.artinus.membership.subscription.dto.SubscriptionRequest;
import com.artinus.membership.subscription.dto.SubscriptionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 구독/해지 유스케이스 오케스트레이터 (2-Phase TX).
 *
 * <ol>
 *   <li>{@link SubscriptionValidator#validate} — read-only TX에서 검증</li>
 *   <li>{@link CsrngClient#fetchRandomBit} — 트랜잭션 <b>밖</b>에서 외부 호출 (커넥션 미점유)</li>
 *   <li>{@link SubscriptionApplier#apply} — 짧은 write TX에서 상태 변경 + 이력 적재</li>
 * </ol>
 *
 * <p>본 메서드에는 {@code @Transactional}을 두지 않는다. validate/apply가 각각 별도 빈이므로
 * AOP 프록시가 정상 동작한다(self-invocation 함정 회피).
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionValidator validator;
    private final SubscriptionApplier applier;
    private final CsrngClient csrngClient;
    private final Clock clock;

    public SubscriptionResponse execute(SubscriptionRequest request) {
        ValidationContext ctx = validator.validate(request);

        int randomBit = csrngClient.fetchRandomBit();
        if (randomBit == 0) {
            throw new ExternalValidationRejectedException(
                    "External validation rejected by csrng (random=0).");
        }

        LocalDateTime occurredAt = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        return applier.apply(ctx, occurredAt);
    }
}
