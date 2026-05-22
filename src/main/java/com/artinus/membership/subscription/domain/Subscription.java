package com.artinus.membership.subscription.domain;

import com.artinus.membership.common.exception.IllegalStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 회원 × 채널 구독 현재 상태. FK는 Long만 사용(@ManyToOne 미사용 — N+1 회피). */
@Entity
@Table(name = "subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "channel_id", nullable = false, updatable = false)
    private Long channelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private SubscriptionState state;

    @Column(name = "subscribed_at")
    private LocalDateTime subscribedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Subscription(Long id, Long memberId, Long channelId, SubscriptionState state,
                         LocalDateTime subscribedAt, LocalDateTime canceledAt,
                         Long version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.memberId = memberId;
        this.channelId = channelId;
        this.state = state;
        this.subscribedAt = subscribedAt;
        this.canceledAt = canceledAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 정책에 따라 상태를 전이하고 subscribed_at / canceled_at 갱신.
     *
     * @throws IllegalStateTransitionException 매트릭스에 정의되지 않은 전이
     */
    public SubscriptionState apply(StateTransitionEvent event, LocalDateTime occurredAt) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }

        SubscriptionState next = StateTransitionPolicy.nextState(this.state, event);
        this.state = next;
        if (event.action() == StateTransitionEvent.ActionType.SUBSCRIBE) {
            if (this.subscribedAt == null) {
                this.subscribedAt = occurredAt;
            }
        } else {
            this.canceledAt = occurredAt;
        }
        this.updatedAt = occurredAt;
        return next;
    }
}
