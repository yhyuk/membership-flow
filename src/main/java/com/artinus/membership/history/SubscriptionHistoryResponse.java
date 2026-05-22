package com.artinus.membership.history;

import com.artinus.membership.subscription.StateTransitionEvent;
import com.artinus.membership.subscription.SubscriptionState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 구독 이력 조회 응답.
 *
 * <p>ASSIGNMENT 명세(§3): 이력 목록 + LLM 자연어 요약을 함께 반환한다. 본 과제 결정상
 * <b>history + summary + status</b> 단일 응답이며, LLM 장애 시에도 HTTP 200으로 응답하고
 * {@code status=DEGRADED}로 클라이언트에 신호한다.</p>
 *
 * <p>handoff §1.3 PII 보호: 응답의 {@code phoneNumber}는 마스킹된 형태(예: 010-****-1234)로
 * 노출한다. 면접 방어: "응답 내 PII 부분 마스킹".</p>
 *
 * <p>{@link #status}는 다음 3종:
 * <ul>
 *   <li>{@code NORMAL} — 이력 1건 이상 + LLM 요약 성공</li>
 *   <li>{@code DEGRADED} — 이력 1건 이상 + LLM 호출 실패(또는 키 미설정) → summary=null</li>
 *   <li>{@code EMPTY} — 이력 0건 → LLM 호출 자체를 건너뜀, summary=null</li>
 * </ul>
 */
@Schema(description = "구독 이력 조회 응답 (history + LLM 요약 + status)")
public record SubscriptionHistoryResponse(

        @Schema(description = "회원 PK", example = "57")
        Long memberId,

        @Schema(description = "마스킹된 휴대폰 번호 (예: 010-****-1234)", example = "010-****-5678")
        String phoneNumber,

        @Schema(description = "최근 20건 이력 (occurred_at DESC). 빈 배열이면 status=EMPTY.")
        List<HistoryItem> histories,

        @Schema(description = "LLM이 생성한 자연어 요약. status=DEGRADED/EMPTY이면 null.",
                example = "2026년 1월 1일 홈페이지를 통해 일반 구독으로 가입한 뒤, 2월 1일 모바일앱에서 프리미엄으로 변경하였습니다.",
                nullable = true)
        String summary,

        @Schema(description = "응답 상태 (NORMAL | DEGRADED | EMPTY)", example = "NORMAL",
                allowableValues = {"NORMAL", "DEGRADED", "EMPTY"})
        Status status,

        @Schema(description = "응답 생성 시각 (서버 시계 기준)", example = "2026-05-20T14:30:00")
        LocalDateTime retrievedAt
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

    /**
     * 단일 이력 행.
     *
     * @param occurredAt    전이 발생 시각
     * @param channelCode   채널 식별 코드
     * @param channelName   채널 표시명
     * @param previousState 전이 이전 상태 (첫 이력은 null)
     * @param nextState     전이 이후 상태
     * @param eventType     SUBSCRIBE / UNSUBSCRIBE
     */
    @Schema(description = "단일 이력 행")
    public record HistoryItem(

            @Schema(description = "전이 발생 시각", example = "2026-01-01T10:00:00")
            LocalDateTime occurredAt,

            @Schema(description = "채널 식별 코드", example = "HOMEPAGE")
            String channelCode,

            @Schema(description = "채널 표시명", example = "홈페이지")
            String channelName,

            @Schema(description = "전이 이전 상태 (첫 이력은 null)", example = "NONE", nullable = true)
            SubscriptionState previousState,

            @Schema(description = "전이 이후 상태", example = "BASIC")
            SubscriptionState nextState,

            @Schema(description = "SUBSCRIBE / UNSUBSCRIBE", example = "SUBSCRIBE")
            StateTransitionEvent.ActionType eventType
    ) {
    }
}
