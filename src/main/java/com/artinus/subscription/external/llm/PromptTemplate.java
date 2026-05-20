package com.artinus.subscription.external.llm;

import com.artinus.subscription.domain.SubscriptionHistory;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Gemini 요약 프롬프트 빌더.
 *
 * <p>handoff §3.6/§1.3 PII 보호 규칙을 코드로 강제한다:
 * <ul>
 *   <li>phoneNumber는 절대 프롬프트에 포함하지 않는다.</li>
 *   <li>memberId 같이 외부 노출 시 PII가 될 수 있는 식별자도 포함하지 않는다.</li>
 *   <li>요약에 사용하는 필드는 {@code occurredAt / channelCode / previousState / nextState / eventType} 5종으로 제한.</li>
 *   <li>이력은 최근 N건만 (호출자에서 이미 제한되어 들어오지만 본 클래스도 그대로 직렬화한다).</li>
 * </ul>
 *
 * <p>시스템 지시문은 클래스 상수에 하드코딩되어 있어 외부 입력으로 prompt-injection을 받지 않는다.</p>
 */
public final class PromptTemplate {

    private PromptTemplate() {
        // 정적 유틸 — 인스턴스화 금지
    }

    /** 시스템 지시문 (하드코딩). 외부 입력으로 변경 불가하여 prompt injection 방어. */
    static final String SYSTEM_INSTRUCTION = """
            당신은 고객 구독 이력 요약 봇입니다. 다음 이력을 200자 이내로 자연어 한국어로 요약합니다.
            규칙:
            - 개인정보(전화번호, 이메일 등)는 절대 포함하지 마십시오.
            - 채널/상태/시점 흐름만 사실적으로 정리하십시오.
            - 추측이나 광고성 문구는 금지입니다.
            """;

    /** 이력 행 직렬화 포맷 (yyyy-MM-dd HH:mm). 분 단위까지 표시. */
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** user 메시지 본문 헤더. 이력 라인이 시작되기 전에 LLM에 컨텍스트를 주는 정적 안내문. */
    private static final String USER_HEADER = "다음은 한 고객의 최근 구독 이력입니다 (시간 오름차순):";

    /**
     * (occurredAt, channelCode, previousState, nextState, eventType) 5필드만 사용해 user 메시지를 만든다.
     *
     * <p>channelCode 매핑은 호출자가 제공한다 — {@link SubscriptionHistory}가 channelId만 보유하므로
     * 채널 메타를 LLM 호출 직전에 인메모리에서 룩업한 결과를 받는다.</p>
     *
     * @param recent           최근 N건 이력 (DESC로 들어오면 본 메서드가 내부에서 ASC로 뒤집어 사용)
     * @param channelCodeById  channelId → channelCode 매핑
     * @return user role 평문 텍스트
     */
    static String buildUserMessage(List<SubscriptionHistory> recent, Map<Long, String> channelCodeById) {
        StringBuilder sb = new StringBuilder(USER_HEADER).append('\n');
        // recent가 DESC로 전달되어도 LLM이 시계열 흐름을 이해하기 쉽도록 ASC로 뒤집는다.
        // 새 리스트를 만들어 호출자의 컬렉션을 변형하지 않는다.
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

    /**
     * Gemini 요청 본문 전체를 빌드한다(시스템 지시문 + user content + generationConfig).
     *
     * @param recent          최근 N건 이력 (이미 N으로 제한된 리스트)
     * @param channelCodeById channelId → channelCode 매핑
     * @return Gemini API에 그대로 직렬화 가능한 {@link GeminiRequest}
     */
    public static GeminiRequest buildRequest(List<SubscriptionHistory> recent, Map<Long, String> channelCodeById) {
        GeminiRequest.Content system = new GeminiRequest.Content(
                null, List.of(new GeminiRequest.Part(SYSTEM_INSTRUCTION)));
        GeminiRequest.Content user = new GeminiRequest.Content(
                "user", List.of(new GeminiRequest.Part(buildUserMessage(recent, channelCodeById))));
        GeminiRequest.GenerationConfig config = new GeminiRequest.GenerationConfig(300, 0.2);
        return new GeminiRequest(system, List.of(user), config);
    }
}
