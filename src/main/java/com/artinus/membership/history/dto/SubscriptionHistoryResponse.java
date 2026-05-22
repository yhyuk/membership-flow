package com.artinus.membership.history.dto;

import com.artinus.membership.subscription.domain.StateTransitionEvent;
import com.artinus.membership.subscription.domain.SubscriptionState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 구독 이력 조회 응답 (history + LLM 요약 + status).
 * phoneNumber는 마스킹된 형태로 노출. status는 NORMAL/DEGRADED/EMPTY.
 */
@Schema(description = "구독 이력 조회 응답")
public record SubscriptionHistoryResponse(

        @Schema(description = "회원 PK", example = "57")
        Long memberId,

        @Schema(description = "마스킹된 휴대폰 번호", example = "010-****-5678")
        String phoneNumber,

        @Schema(description = "최근 20건 이력 (occurred_at DESC)")
        List<HistoryItem> histories,

        @Schema(description = "LLM 요약. DEGRADED/EMPTY이면 null", nullable = true)
        String summary,

        @Schema(description = "NORMAL(요약 성공) / DEGRADED(LLM 실패) / EMPTY(이력 없음)",
                allowableValues = {"NORMAL", "DEGRADED", "EMPTY"})
        Status status,

        @Schema(description = "응답 생성 시각", example = "2026-05-20T14:30:00")
        LocalDateTime retrievedAt
) {

    public enum Status {
        NORMAL,
        DEGRADED,
        EMPTY
    }

    @Schema(description = "단일 이력 행")
    public record HistoryItem(
            @Schema(description = "전이 발생 시각", example = "2026-01-01T10:00:00")
            LocalDateTime occurredAt,

            @Schema(description = "채널 코드", example = "HOMEPAGE")
            String channelCode,

            @Schema(description = "채널 표시명", example = "홈페이지")
            String channelName,

            @Schema(description = "전이 이전 상태 (첫 이력은 null)", nullable = true)
            SubscriptionState previousState,

            @Schema(description = "전이 이후 상태")
            SubscriptionState nextState,

            @Schema(description = "SUBSCRIBE / UNSUBSCRIBE")
            StateTransitionEvent.ActionType eventType
    ) {
    }
}
