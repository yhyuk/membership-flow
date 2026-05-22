package com.artinus.membership.subscription.persistence;

import com.artinus.membership.subscription.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 회원 × 채널은 UNIQUE이므로 단건 조회. 동시성은 @Version 낙관락으로. */
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByMemberIdAndChannelId(Long memberId, Long channelId);
}
