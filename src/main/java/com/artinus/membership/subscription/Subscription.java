package com.artinus.membership.subscription;

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

/**
 * 회원 × 채널 구독 현재 상태.
 *
 * <p>Long FK로만 회원/채널을 참조한다. @ManyToOne 객체 그래프는
 * 사용하지 않는다 — N+1 회피 및 단순성(handoff §3 amendments).</p>
 *
 * <p>상태 변경은 {@link #apply(StateTransitionEvent, LocalDateTime)}로 캡슐화되어
 * subscribed_at / canceled_at 필드를 적절히 갱신한다. 전이 가능 여부는
 * {@link StateTransitionPolicy}에 위임한다.</p>
 */
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
     * 정책에 따라 상태를 전이하고 부수 필드(subscribed_at / canceled_at)를 갱신한다.
     *
     * <p>전이 불가능한 조합이면 {@link IllegalStateTransitionException}을 던진다.
     * 호출자는 이 예외를 422 {@code INVALID_STATE_TRANSITION}으로 매핑한다(Phase 4 책임).</p>
     *
     * @param event       처리할 이벤트
     * @param occurredAt  이벤트 시각 (이력 적재 및 subscribed_at/canceled_at 갱신용)
     * @return 전이 후 상태 (호출자가 history 적재에 활용)
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
