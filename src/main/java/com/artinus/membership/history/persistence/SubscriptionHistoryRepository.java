package com.artinus.membership.history.persistence;

import com.artinus.membership.history.domain.SubscriptionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 이력 영속 어댑터(append-only). idx_history_member_occurred 인덱스가 DESC 정렬을 지원. */
public interface SubscriptionHistoryRepository extends JpaRepository<SubscriptionHistory, Long> {

    List<SubscriptionHistory> findTop20ByMemberIdOrderByOccurredAtDesc(Long memberId);
}
