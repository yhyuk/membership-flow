package com.artinus.subscription.application;

import com.artinus.subscription.application.exception.ConcurrentModificationException;
import com.artinus.subscription.domain.Channel;
import com.artinus.subscription.domain.Member;
import com.artinus.subscription.domain.StateTransitionEvent;
import com.artinus.subscription.domain.Subscription;
import com.artinus.subscription.domain.SubscriptionHistory;
import com.artinus.subscription.domain.SubscriptionState;
import com.artinus.subscription.infrastructure.repository.MemberRepository;
import com.artinus.subscription.infrastructure.repository.SubscriptionHistoryRepository;
import com.artinus.subscription.infrastructure.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 2-Phase TX의 3단계 — write 트랜잭션.
 *
 * <p>외부 API(csrng) 호출이 끝난 직후 짧은 write TX에서 상태 변경 + 이력 적재를 수행한다.
 * 본 트랜잭션은 DB 작업만 수행하므로 커넥션 점유 시간은 수~수십 ms로 제한된다(handoff §3.1).</p>
 *
 * <p>주요 처리:
 * <ul>
 *   <li>신규 회원이면 INSERT (UNIQUE 제약 위반 시 동시 가입 충돌로 409 변환).</li>
 *   <li>구독 행이 없으면 새로 생성 (NONE 상태로). 있으면 재조회 후 {@link Subscription#apply}로 전이.</li>
 *   <li>낙관락 충돌(@Version 불일치)은 {@link ConcurrentModificationException}으로 변환되어 409로 매핑.</li>
 *   <li>전이 후 {@link SubscriptionHistory}를 append.</li>
 * </ul>
 *
 * <p>구조 분리 사유: {@code @Transactional} self-invocation 함정 회피 + 책임 단일화.</p>
 */
@Component
@RequiredArgsConstructor
public class SubscriptionApplier {

    private final MemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionHistoryRepository subscriptionHistoryRepository;

    @Transactional
    public SubscriptionResult apply(ValidationContext ctx, LocalDateTime occurredAt) {
        try {
            Member member = resolveOrCreateMember(ctx, occurredAt);
            Channel channel = ctx.channel();

            Subscription subscription = resolveOrCreateSubscription(member, channel, occurredAt);
            SubscriptionState previousState = subscription.getState();

            // 전이 적용 — 정책 위반 시 IllegalStateTransitionException (Validator에서 이미 통과했으나
            // 동시성으로 인해 currentState가 바뀌었을 가능성 대비).
            SubscriptionState nextState = subscription.apply(ctx.event(), occurredAt);
            // save로 명시적 flush 유도 (낙관락 충돌은 commit 시점이 아니라 여기서 발견되도록).
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

            return new SubscriptionResult(
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
            // members.phone_number UNIQUE 충돌 또는 subscriptions UNIQUE(member_id, channel_id) 충돌.
            // 동시 가입 경합 (handoff M-4).
            throw new ConcurrentModificationException(
                    "Concurrent insert conflict. Please retry.", e);
        }
    }

    private Member resolveOrCreateMember(ValidationContext ctx, LocalDateTime occurredAt) {
        if (ctx.member() != null) {
            return ctx.member();
        }
        // Validator 단계에서 회원이 없었으므로 INSERT. UNIQUE(phone_number) 충돌은 위 catch로 처리.
        Member created = Member.builder()
                .phoneNumber(ctx.normalizedPhoneNumber())
                .version(0L)
                .createdAt(occurredAt)
                .updatedAt(occurredAt)
                .build();
        return memberRepository.save(created);
    }

    private Subscription resolveOrCreateSubscription(Member member, Channel channel, LocalDateTime occurredAt) {
        Optional<Subscription> existing = subscriptionRepository
                .findByMemberIdAndChannelId(member.getId(), channel.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        return Subscription.builder()
                .memberId(member.getId())
                .channelId(channel.getId())
                .state(SubscriptionState.NONE)
                .version(0L)
                .createdAt(occurredAt)
                .updatedAt(occurredAt)
                .build();
    }
}
