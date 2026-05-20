package com.artinus.subscription.application;

import com.artinus.subscription.domain.Channel;
import com.artinus.subscription.domain.Member;
import com.artinus.subscription.domain.SubscriptionHistory;

import java.util.List;
import java.util.Map;

/**
 * 이력 조회 + LLM 요약 통합 결과.
 *
 * <p>Presentation 계층의 {@code SubscriptionHistoryResponse}로 매핑된다.</p>
 *
 * @param member       조회된 회원
 * @param histories    최근 20건 이력 (DESC)
 * @param channelsById 이력에 등장한 채널들의 메타 (code/name)
 * @param summary      LLM 자연어 요약. status=DEGRADED/EMPTY이면 null
 * @param status       응답 상태 (NORMAL | DEGRADED | EMPTY)
 */
public record HistoryResult(
        Member member,
        List<SubscriptionHistory> histories,
        Map<Long, Channel> channelsById,
        String summary,
        Status status
) {

    /**
     * 응답 상태 분류.
     *
     * <ul>
     *   <li>{@link #NORMAL} — 이력 1건 이상 + LLM 요약 성공</li>
     *   <li>{@link #DEGRADED} — 이력 1건 이상 + LLM 호출 실패 (키 미설정/4xx/5xx/timeout 모두 포함)</li>
     *   <li>{@link #EMPTY} — 이력 0건 → LLM 호출 자체를 건너뜀</li>
     * </ul>
     */
    public enum Status {
        NORMAL,
        DEGRADED,
        EMPTY
    }
}
