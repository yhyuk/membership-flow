package com.artinus.membership.history;

import com.artinus.membership.history.SubscriptionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 구독 이력 영속 어댑터.
 *
 * <p>append-only 로그. Phase 4에서는 INSERT만, Phase 5에서 최근 20건 조회를 추가한다.</p>
 *
 * <p>인덱스: V1__init.sql의 {@code KEY idx_history_member_occurred (member_id, occurred_at DESC)}가
 * {@link #findTop20ByMemberIdOrderByOccurredAtDesc(Long)}의 정렬/필터링을 직접 지원한다 (추가 인덱스 불필요).</p>
 */
public interface SubscriptionHistoryRepository extends JpaRepository<SubscriptionHistory, Long> {

    /**
     * 특정 회원의 최근 20건 이력을 occurred_at DESC 순으로 조회.
     *
     * <p>Spring Data JPA 키워드 derived query로 표현되며, 별도 {@code @Query}나 PageRequest 없이도
     * SQL {@code ORDER BY occurred_at DESC LIMIT 20}을 생성한다. handoff §1.3/§3.6에 따라
     * LLM 프롬프트와 응답 모두 동일하게 최근 20건으로 제한된다.</p>
     */
    List<SubscriptionHistory> findTop20ByMemberIdOrderByOccurredAtDesc(Long memberId);
}
