package com.artinus.membership.subscription.persistence;

import com.artinus.membership.subscription.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 회원당 단일 행 (UNIQUE member_id). 동시성은 @Version 낙관락으로. */
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByMemberId(Long memberId);
}
