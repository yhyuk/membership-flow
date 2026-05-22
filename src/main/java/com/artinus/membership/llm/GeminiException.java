package com.artinus.membership.llm;

/** Gemini 호출 실패. HistoryService가 catch하여 status=DEGRADED + summary=null로 변환. */
public class GeminiException extends RuntimeException {

    public GeminiException(String message) {
        super(message);
    }

    public GeminiException(String message, Throwable cause) {
        super(message, cause);
    }
}
