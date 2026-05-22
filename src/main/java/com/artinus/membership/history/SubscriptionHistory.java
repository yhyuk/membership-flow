package com.artinus.membership.history;

import com.artinus.membership.subscription.StateTransitionEvent;
import com.artinus.membership.subscription.SubscriptionState;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 구독 상태 전이의 INSERT-only append log.
 *
 * <p>member_id / channel_id는 LLM 요약 등 조회 성능을 위한 비정규화 컬럼.
 * previous_state는 첫 이력일 때 NULL 허용.</p>
 */
@Entity
@Table(name = "subscription_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private Long subscriptionId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "channel_id", nullable = false, updatable = false)
    private Long channelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state", length = 20, updatable = false)
    private SubscriptionState previousState;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_state", nullable = false, length = 20, updatable = false)
    private SubscriptionState nextState;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20, updatable = false)
    private StateTransitionEvent.ActionType eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Builder
    private SubscriptionHistory(Long id, Long subscriptionId, Long memberId, Long channelId,
                                SubscriptionState previousState, SubscriptionState nextState,
                                StateTransitionEvent.ActionType eventType, LocalDateTime occurredAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.memberId = memberId;
        this.channelId = channelId;
        this.previousState = previousState;
        this.nextState = nextState;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
    }
}
