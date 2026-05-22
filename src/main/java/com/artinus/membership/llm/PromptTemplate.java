package com.artinus.membership.llm;

import com.artinus.membership.history.domain.SubscriptionHistory;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Gemini 요약 프롬프트 빌더.
 * PII(전화번호, memberId 등)는 프롬프트에 포함하지 않는다. 시스템 지시문은 하드코딩(prompt injection 방어).
 */
public final class PromptTemplate {

    private PromptTemplate() {
    }

    static final String SYSTEM_INSTRUCTION = """
            당신은 고객 구독 이력 요약 봇입니다. 다음 이력을 200자 이내로 자연어 한국어로 요약합니다.
            규칙:
            - 개인정보(전화번호, 이메일 등)는 절대 포함하지 마십시오.
            - 채널/상태/시점 흐름만 사실적으로 정리하십시오.
            - 추측이나 광고성 문구는 금지입니다.
            """;

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String USER_HEADER = "다음은 한 고객의 최근 구독 이력입니다 (시간 오름차순):";

    static String buildUserMessage(List<SubscriptionHistory> recent, Map<Long, String> channelCodeById) {
        StringBuilder sb = new StringBuilder(USER_HEADER).append('\n');
        // DESC로 들어와도 ASC로 정렬해 LLM이 시계열 흐름을 이해하기 쉽게.
        List<SubscriptionHistory> ordered = recent.stream()
                .sorted((a, b) -> a.getOccurredAt().compareTo(b.getOccurredAt()))
                .toList();

        for (SubscriptionHistory h : ordered) {
            String channelCode = channelCodeById.getOrDefault(h.getChannelId(), "UNKNOWN");
            String previousState = h.getPreviousState() == null ? "NONE" : h.getPreviousState().name();
            sb.append("- ")
                    .append(ISO_LOCAL.format(h.getOccurredAt()))
                    .append(" | channel=").append(channelCode)
                    .append(" | ").append(previousState).append(" -> ").append(h.getNextState().name())
                    .append(" | event=").append(h.getEventType().name())
                    .append('\n');
        }
        return sb.toString();
    }

    public static GeminiRequest buildRequest(List<SubscriptionHistory> recent, Map<Long, String> channelCodeById) {
        GeminiRequest.Content system = new GeminiRequest.Content(
                null, List.of(new GeminiRequest.Part(SYSTEM_INSTRUCTION)));
        GeminiRequest.Content user = new GeminiRequest.Content(
                "user", List.of(new GeminiRequest.Part(buildUserMessage(recent, channelCodeById))));
        GeminiRequest.GenerationConfig config = new GeminiRequest.GenerationConfig(300, 0.2);
        return new GeminiRequest(system, List.of(user), config);
    }
}
