package com.artinus.membership.llm;


/**
 * Gemini(LLM) 외부 API 호출 실패를 표현하는 도메인 예외.
 *
 * <p>fail-closed 정책에 따라 어댑터는 어떤 임의 기본값/Fallback 텍스트도 반환하지 않고
 * 모든 실패 경로(IO, timeout, 5xx, 4xx, 안전 필터, 빈 응답, API Key 미설정 등)를
 * 본 예외 단일 타입으로 전파한다. 상위 서비스({@code HistoryService})가 본 예외를 catch하여
 * {@code status=DEGRADED + summary=null}로 응답을 degrade한다.</p>
 *
 * <p>서브타입을 만들지 않는다 (오버엔지니어링 방지). 실패 원인 세분화는 message로만 표현한다.
 * 메시지 예: "api-key not configured" / "timeout" / "upstream 5xx" / "blocked by safety filter" / "empty candidates".</p>
 */
public class GeminiException extends RuntimeException {

    public GeminiException(String message) {
        super(message);
    }

    public GeminiException(String message, Throwable cause) {
        super(message, cause);
    }
}
