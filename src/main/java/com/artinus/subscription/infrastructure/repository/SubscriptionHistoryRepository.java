package com.artinus.subscription.infrastructure.repository;

import com.artinus.subscription.domain.SubscriptionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 구독 이력 영속 어댑터.
 *
 * <p>append-only 로그. Phase 4에서는 INSERT만 사용한다.
 * 조회용 메서드는 Phase 5(이력 조회 API)에서 추가된다.</p>
 */
public interface SubscriptionHistoryRepository extends JpaRepository<SubscriptionHistory, Long> {
}
