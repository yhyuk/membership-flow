package com.artinus.membership.subscription;

import com.artinus.membership.subscription.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 구독 영속 어댑터.
 *
 * <p>한 회원이 채널별로 단일 행을 유지한다(UNIQUE(member_id, channel_id), V1__init.sql).
 * 따라서 (memberId, channelId) 조합 조회 + 낙관락(@Version)만으로 동시성을 처리한다.</p>
 */
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** 회원 × 채널 단건 조회. UNIQUE 제약 활용. */
    Optional<Subscription> findByMemberIdAndChannelId(Long memberId, Long channelId);
}
