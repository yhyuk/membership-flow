# 03. 외부 API 장애 대응

> ASSIGNMENT 요구: "외부 API(csrng) 호출 시 발생할 수 있는 장애 상황에 대한 대응 전략을 구현."
> 본 서비스는 csrng(트랜잭션 검증)와 Gemini(LLM 요약) 두 외부 의존성을 가지며, 둘의 **장애 영향도가 다르므로 대응 전략도 다르다.**

---

## 1. 외부 의존성과 장애 영향도

| 외부 API | 역할 | 실패 시 영향 | 전략 |
|---|---|---|---|
| **csrng** | 트랜잭션 커밋/롤백 결정 | 구독/해지 불가 (핵심 기능) | Retry + CircuitBreaker, 실패 시 **502로 거부** |
| **Gemini** | 이력 자연어 요약 (보조) | 요약만 누락, 이력은 정상 | Retry + CircuitBreaker, 실패 시 **DEGRADED로 흡수** |

핵심 원칙: **csrng 실패는 트랜잭션을 막지만, Gemini 실패는 응답을 막지 않습니다.**

---

## 2. 2-Phase 트랜잭션 — 외부 호출을 TX 밖으로

csrng 호출을 DB 트랜잭션 안에서 하면, 느린 외부 응답(최대 2s read timeout)이 DB 커넥션을 점유해 커넥션 풀 고갈을 유발합니다. 이를 막으려고 검증/외부호출/적용을 3단계로 분리합니다.

```
[read-only TX] 검증  →  [TX 밖] csrng 호출  →  [write TX] 상태 변경 + 이력 적재
```

- csrng가 `random=0`을 반환하거나 장애로 실패하면 **write TX에 진입하지 않는다** → 자연스러운 롤백 효과
- 상세 시퀀스는 [01-architecture.md](01-architecture.md) §4 참고

---

## 3. Resilience4j 설정

동기 `RestClient`를 사용하므로 **Retry + CircuitBreaker만** 적용합니다. TimeLimiter는 별도 스레드 풀(비동기)을 요구하므로 사용하지 않고, 대신 `RestClient` 자체의 connect/read timeout으로 시간 제한을 겁니다.

### 3.1 csrng

```yaml
retry.csrng:
  max-attempts: 3
  wait-duration: 200ms
  retry-exceptions: [ResourceAccessException, HttpServerErrorException]  # IO/5xx만 재시도
  ignore-exceptions: [CsrngException]                                   # 4xx/비즈니스 오류는 재시도 무의미

circuitbreaker.csrng:
  sliding-window-size: 10
  minimum-number-of-calls: 5
  failure-rate-threshold: 50%
  wait-duration-in-open-state: 10s
```

- **타임아웃**: connect 1s / read 2s
- **재시도 대상 구분**: 5xx·IO 오류만 재시도합니다. 4xx나 `status≠success` 같은 비즈니스 오류(`CsrngException`)는 재시도해도 결과가 같으므로 즉시 실패합니다.
- **CircuitBreaker**: 최근 10건 중 50% 실패 시 Open → 10초간 빠른 실패(fail-fast)로 외부 장애 전파를 차단합니다.

### 3.2 Gemini

```yaml
retry.gemini:
  max-attempts: 2
  wait-duration: 500ms
circuitbreaker.gemini:
  wait-duration-in-open-state: 30s   # LLM은 더 보수적으로
```

- **타임아웃**: connect 2s / read 10s (LLM 응답이 길다)
- Aspect order: CircuitBreaker(1) → Retry(2). CB가 바깥에서 전체 재시도 묶음을 감쌉니다.

---

## 4. csrng 장애 경로 — 502로 거부

| 장애 | 처리 |
|---|---|
| 5xx / timeout / IO | Retry 3회 소진 후 `HttpServerErrorException`/`ResourceAccessException` 전파 → **502 EXTERNAL_API_UNAVAILABLE** |
| 4xx / 빈 응답 / status≠success | `CsrngException` 즉시 → **502** (재시도 안 함) |
| CircuitBreaker Open | 즉시 fail-fast → **502** |
| `random == 0` | `ExternalValidationRejectedException` → **422 EXTERNAL_VALIDATION_REJECTED** (장애 아님, 정상 거부) |

모든 csrng 실패 경로에서 **write TX에 진입하지 않으므로** 데이터 정합성이 보장됩니다.

---

## 5. Gemini 장애 경로 — DEGRADED로 흡수

이력 조회는 LLM 요약 실패가 사용자 응답을 막아선 안 됩니다(요약은 보조 기능). `HistoryService`가 모든 LLM 실패를 흡수합니다.

```java
try {
    String summary = geminiClient.summarize(...);
    return toResponse(snapshot, summary, NORMAL);
} catch (GeminiException | HttpServerErrorException | ResourceAccessException e) {
    return toResponse(snapshot, null, DEGRADED);   // HTTP 200 유지
}
```

| 실패 유형 | 결과 |
|---|---|
| api-key 미설정 | HTTP 호출 자체 생략(fail-fast) → DEGRADED |
| 4xx / 5xx / timeout / CB Open | DEGRADED |
| 안전 필터 차단(finishReason=SAFETY) | DEGRADED |

이력 0건이면 LLM을 아예 호출하지 않고 `EMPTY`로 응답해 불필요한 비용/지연을 제거합니다.

---

## 6. 보안 — LLM 프롬프트 보호

- **PII 미포함**: 전화번호·memberId를 프롬프트에 넣지 않는다. 채널/상태/시점만 전달
- **시스템 지시문 하드코딩**: prompt injection 방어. 사용자 입력이 시스템 역할로 섞이지 않음
- **출력 제어**: `ThinkingConfig.thinkingBudget=0`(reasoning 토큰 비활성) + `maxOutputTokens=512`로 비용·지연 상한

---

## 7. 운영 보강 (미적용)

CircuitBreaker open 메트릭 → CloudWatch 알람, LLM 호출 캐싱, DLQ 재처리 등은 [05-limitations.md](05-limitations.md)와 [04-cloud-infrastructure.md](04-cloud-infrastructure.md)를 참고하세요.
