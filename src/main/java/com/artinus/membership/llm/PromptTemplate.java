package com.artinus.membership.llm;

import com.artinus.membership.history.domain.SubscriptionHistory;
import com.artinus.membership.subscription.domain.SubscriptionState;
import com.artinus.membership.subscription.domain.SubscriptionStateLabel;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Gemini 요약 프롬프트 빌더.
 * PII(전화번호, memberId 등)는 프롬프트에 포함하지 않는다. 시스템 지시문은 하드코딩(prompt injection 방어).
 * 채널/상태는 입력 단계에서 한글 라벨로 치환하여 LLM의 추측·환각 가능성을 줄인다.
 */
public final class PromptTemplate {

    private PromptTemplate() {
    }

    static final String SYSTEM_INSTRUCTION = """
            당신은 고객 구독 이력 요약 봇입니다. 주어진 이력을 200자 이내 한국어 평문으로 요약합니다.

            규칙:
            - 데이터에 있는 채널명과 상태 라벨을 그대로 사용하십시오. (영문 코드 노출 금지)
            - 시간 순서대로 시점, 채널, 상태 변화를 사실적으로 정리하십시오.
            - 이력이 많으면 모든 이벤트를 나열하지 말고 시작, 주요 전환, 현재 상태 중심으로 압축하십시오.
            - 마지막 줄의 "다음" 상태가 현재 상태입니다. 문장 말미에 현재 상태를 명시하십시오.
            - 추측이나 데이터에 없는 사실은 추가하지 마십시오.
            """;

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String USER_HEADER = "다음은 한 고객의 최근 구독 이력입니다 (시간 오름차순):";

    static String buildUserMessage(List<SubscriptionHistory> recent, Map<Long, String> channelNameById) {
        StringBuilder sb = new StringBuilder(USER_HEADER).append('\n');
        // DESC로 들어와도 ASC로 정렬해 LLM이 시계열 흐름을 이해하기 쉽게.
        List<SubscriptionHistory> ordered = recent.stream()
                .sorted(Comparator.comparing(SubscriptionHistory::getOccurredAt))
                .toList();

        for (SubscriptionHistory h : ordered) {
            String channelName = channelNameById.getOrDefault(h.getChannelId(), "알 수 없음");
            String previousLabel = SubscriptionStateLabel.of(
                    h.getPreviousState() == null ? SubscriptionState.NONE : h.getPreviousState());
            String nextLabel = SubscriptionStateLabel.of(h.getNextState());
            sb.append("- ")
                    .append(ISO_LOCAL.format(h.getOccurredAt()))
                    .append(" | 채널=").append(channelName)
                    .append(" | 이전=").append(previousLabel)
                    .append(" → 다음=").append(nextLabel)
                    .append('\n');
        }
        return sb.toString();
    }

    public static GeminiRequest buildRequest(List<SubscriptionHistory> recent, Map<Long, String> channelNameById) {
        GeminiRequest.Content system = new GeminiRequest.Content(
                null, List.of(new GeminiRequest.Part(SYSTEM_INSTRUCTION)));
        GeminiRequest.Content user = new GeminiRequest.Content(
                "user", List.of(new GeminiRequest.Part(buildUserMessage(recent, channelNameById))));
        // thinkingBudget=0 — 2.5-flash 같은 reasoning 모델에서 thinking 토큰이 출력을 잠식하지 않도록 비활성.
        GeminiRequest.GenerationConfig config = new GeminiRequest.GenerationConfig(
                512, 0.2, new GeminiRequest.ThinkingConfig(0));
        return new GeminiRequest(system, List.of(user), config);
    }
}
