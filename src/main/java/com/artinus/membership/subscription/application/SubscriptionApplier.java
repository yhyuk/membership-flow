package com.artinus.membership.subscription.application;

import com.artinus.membership.channel.Channel;
import com.artinus.membership.common.exception.ConcurrentModificationException;
import com.artinus.membership.history.domain.SubscriptionHistory;
import com.artinus.membership.history.persistence.SubscriptionHistoryRepository;
import com.artinus.membership.member.Member;
import com.artinus.membership.member.MemberRepository;
import com.artinus.membership.subscription.domain.Subscription;
import com.artinus.membership.subscription.domain.SubscriptionState;
import com.artinus.membership.subscription.dto.SubscriptionResponse;
import com.artinus.membership.subscription.persistence.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 2-Phase TX의 3단계 — 짧은 write 트랜잭션.
 * Service와 별도 빈으로 둔 이유: {@code @Transactional} self-invocation 회피.
 *
 * <p>UNIQUE 충돌(동시 가입)과 낙관락 충돌은 모두 {@link ConcurrentModificationException}으로 변환해 409 매핑.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionApplier {

    private final MemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionHistoryRepository subscriptionHistoryRepository;

    @Transactional
    public SubscriptionResponse apply(ValidationContext ctx, LocalDateTime occurredAt) {
        try {
            Member member = resolveOrCreateMember(ctx, occurredAt);
            Channel channel = ctx.channel();

            Subscription subscription = resolveOrCreateSubscription(member, channel, occurredAt);
            SubscriptionState previousState = subscription.getState();

            // Validator 통과 후에도 동시성으로 currentState가 변했을 수 있어 도메인이 재검증.
            SubscriptionState nextState = subscription.apply(ctx.event(), channel.getId(), occurredAt);
            // 명시적 flush — 낙관락 충돌을 커밋이 아니라 여기서 발견.
            Subscription persisted = subscriptionRepository.save(subscription);

            SubscriptionHistory history = SubscriptionHistory.builder()
                    .subscriptionId(persisted.getId())
                    .memberId(member.getId())
                    .channelId(channel.getId())
                    .previousState(previousState)
                    .nextState(nextState)
                    .eventType(ctx.event().action())
                    .occurredAt(occurredAt)
                    .build();
            subscriptionHistoryRepository.save(history);

            return new SubscriptionResponse(
                    persisted.getId(),
                    member.getId(),
                    channel.getCode(),
                    previousState,
                    nextState,
                    ctx.event().action(),
                    occurredAt);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentModificationException(
                    "Subscription was modified concurrently. Please retry.", e);
        } catch (DataIntegrityViolationException e) {
            // members.phone_number 또는 subscriptions(member_id, channel_id) UNIQUE 충돌.
            throw new ConcurrentModificationException(
                    "Concurrent insert conflict. Please retry.", e);
        }
    }

    private Member resolveOrCreateMember(ValidationContext ctx, LocalDateTime occurredAt) {
        if (ctx.member() != null) {
            return ctx.member();
        }
        Member created = Member.builder()
                .phoneNumber(ctx.normalizedPhoneNumber())
                .version(0L)
                .createdAt(occurredAt)
                .updatedAt(occurredAt)
                .build();
        return memberRepository.save(created);
    }

    private Subscription resolveOrCreateSubscription(Member member, Channel channel, LocalDateTime occurredAt) {
        Optional<Subscription> existing = subscriptionRepository.findByMemberId(member.getId());
        return existing.orElseGet(() -> Subscription.builder()
                .memberId(member.getId())
                .channelId(channel.getId())
                .state(SubscriptionState.NONE)
                .version(0L)
                .createdAt(occurredAt)
                .updatedAt(occurredAt)
                .build());
    }
}
