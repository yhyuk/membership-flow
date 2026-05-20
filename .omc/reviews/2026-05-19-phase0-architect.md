# Phase 0 Architect Review — ARTINUS 구독 서비스 아키텍처

## 1. 상태 머신 검증

### 1.1 ASSIGNMENT.md 전이 매트릭스 (정규화)

| 액션 | 현재 상태 | 허용 대상 상태 |
|------|-----------|----------------|
| SUBSCRIBE | NONE | BASIC, PREMIUM |
| SUBSCRIBE | BASIC | PREMIUM |
| SUBSCRIBE | PREMIUM | _(불가)_ |
| UNSUBSCRIBE | PREMIUM | BASIC, NONE |
| UNSUBSCRIBE | BASIC | NONE |
| UNSUBSCRIBE | NONE | _(불가)_ |

### 1.2 계획서 설계 평가

계획서(라인 58-66)의 `canTransitionTo(action, target)` 설계는 위 매트릭스를 정확히 반영한다. **Endorse.**

### 1.3 모서리 케이스 분석

| 케이스 | 현재 상태 → 대상 상태 | 예상 동작 | 계획서 커버 여부 |
|--------|----------------------|-----------|-----------------|
| 같은 상태로 재구독 (BASIC→BASIC) | SUBSCRIBE(BASIC→BASIC) | 거부(422) | **암묵적.** `canTransitionTo`가 동일 상태를 허용 목록에 포함하지 않으므로 거부됨. 단, 테스트 케이스에 명시 필요. |
| NONE에서 해지 시도 (NONE→NONE) | UNSUBSCRIBE(NONE→NONE) | 거부(422) | **암묵적.** NONE의 해지 허용 집합이 비어있으므로 거부됨. 역시 테스트 케이스 명시 필요. |
| PREMIUM→NONE 직접 해지 | UNSUBSCRIBE(PREMIUM→NONE) | **허용** | **커버됨.** 과제 원문(라인 87)이 PREMIUM→{BASIC, NONE} 명시. 단일 스텝 직접 전이이며 멀티-스텝이 아님. |
| 최초 회원(DB 미존재) 구독 | SUBSCRIBE(null→BASIC) | Member 생성 + BASIC 전이 | **계획서 라인 69**: "최초 회원은 ... 어떤 상태로든 가입 가능". Step 4 수용 기준에도 "신규 회원 → PREMIUM 구독 성공 (Member 생성 + 이력 1건)" 명시. 커버됨. |
| 최초 회원이 NONE으로 구독 시도 | SUBSCRIBE(null→NONE) | 의미 없는 요청. 거부(422) | **미명시.** `targetStatus=NONE`으로 구독 요청은 과제 원문에서도 허용하지 않음. `canTransitionTo`에서 NONE→NONE은 거부되지만, 신규 회원(상태 없음)→NONE 경로는 별도 검증 필요. |

### 1.4 enum 단일 책임 vs Policy 클래스

계획서는 `SubscriptionStatus` enum 내부 메서드 + 별도 `StateTransitionPolicy` 도메인 규칙 객체를 모두 언급한다(라인 65, 91). 이 이중 구조의 책임 분배가 불명확하다.

| 옵션 | 장점 | 단점 |
|------|------|------|
| enum 내부 `canTransitionTo` 단독 | 단순, 자기 설명적, 테스트 용이 | 채널 검증 등 추가 규칙 통합 시 enum 비대 |
| `StateTransitionPolicy` 별도 클래스 단독 | 채널+상태를 결합한 복합 규칙 캡슐화 가능, OCP 준수 | 간접 계층 추가 |
| 혼합(현재 계획서) | 의도가 불명확하면 중복 검증 위험 | 어디가 single source of truth인지 혼란 |

**권고:** `StateTransitionPolicy`를 single source of truth로 삼고, enum은 상태 값만 보유. Policy가 `(action, currentStatus, targetStatus, channel)` 4-tuple을 받아 검증. enum에 전이 로직을 넣지 않는다. 이유: 채널 권한 검증과 상태 전이 검증이 결합된 도메인 규칙은 단일 Policy 클래스가 응집도가 높다.

---

## 2. 트랜잭션 경계 분석

### 2.1 계획서의 현재 설계

계획서(라인 195-196): `@Transactional` 내부에서 **상태 검증/락 → 외부 호출(csrng) → 상태 변경 → 이력 적재** 순서로 한 트랜잭션.

### 2.2 DB 커넥션 점유 시간 최악 시나리오

```
TimeLimiter timeout: 2s
Retry: 3회 (max-attempts=3)
Backoff: 200ms → 400ms (exponential, multiplier=2)
```

**최악 계산:**
- 1차 시도: 2s (timeout) + 200ms (backoff) = 2.2s
- 2차 시도: 2s (timeout) + 400ms (backoff) = 2.4s
- 3차 시도: 2s (timeout) = 2s
- **합계: 6.6초**

HikariCP 기본 `connectionTimeout`은 30초, `maximumPoolSize`는 10이다. 동시 요청 10개가 모두 csrng timeout에 걸리면 6.6초 × 10 = 66초간 모든 커넥션이 점유되어 **DB 커넥션 풀 고갈**이 발생한다. 이후 요청은 HikariCP에서 `SQLTransientConnectionException`을 받는다.

Virtual Thread 환경에서는 스레드 수 제한이 사실상 없으므로 동시 요청 폭증 시 커넥션 풀 고갈 위험이 플랫폼 스레드 모델보다 **더 크다.** Carrier thread는 적지만 VT가 수백 개 생성될 수 있고, 각각이 JDBC 커넥션을 잡으면 풀이 즉시 소진된다.

### 2.3 대안 평가

| 대안 | 설명 | 장점 | 단점 | 과제 적합도 |
|------|------|------|------|-------------|
| **현재(All-in-One TX)** | 전체를 한 트랜잭션으로 | 구현 단순, 원자성 보장 | 커넥션 장기 점유, 외부 장애 시 DB 풀 고갈 | 구현 단순성은 과제 평가에 유리 |
| **Saga(2-TX)** | TX1: 상태 검증만 → 외부 호출(TX 밖) → TX2: 상태 변경+이력 | 커넥션 점유 최소화 | TX1~TX2 사이 동시 요청 충돌 가능, 복잡도 증가 | 과도한 엔지니어링으로 보일 수 있음 |
| **Outbox** | TX: 상태 변경+outbox 적재 → async로 외부 호출 → 결과에 따라 보상 | 완전 비동기, 확장성 최고 | 과제 요구사항("외부 API 응답에 따라 트랜잭션 커밋/롤백")과 상충 | **부적합.** 과제가 동기 롤백을 명시 |
| **2-Phase(권장)** | TX1: 상태 검증+낙관락 버전 조회 → csrng 호출(TX 밖) → TX2: 버전 재검증+상태 변경+이력 | 커넥션 점유 최소, 낙관락으로 중간 충돌 감지 | TX1에서 읽은 version과 TX2 시점 version 불일치 시 재시도 필요 | **최적 균형** |

### 2.4 권장안: 2-Phase Approach

```
Phase 1 (Read-Only, Short TX or no TX):
  - Member 조회 (version 기록)
  - Channel 권한 검증
  - 상태 전이 규칙 검증

Phase 2 (No TX):
  - csrng 외부 호출 (Resilience4j 적용)
  - random=0 → 즉시 예외, 상태 변경 없음

Phase 3 (Write TX, 짧음):
  - Member 재조회 + version 검증 (낙관락)
  - 상태 변경
  - 이력 적재
  - 커밋
```

이 방식은 DB 커넥션 점유를 Phase 3의 수~수십 ms로 제한하면서, 과제 요구사항("외부 API 응답에 따라 트랜잭션 커밋/롤백")을 충족한다. random=0이면 Phase 3에 진입하지 않으므로 "롤백" 시맨틱도 자연스럽다(변경 자체가 없음).

단, 이 방식은 Phase 1~3 사이에 다른 요청이 상태를 변경할 수 있으나, Phase 3의 낙관락이 이를 감지하여 `OptimisticLockException`(409)으로 안전하게 거부한다.

**평가자에게 합리적으로 보이는 균형점:** README에 "외부 API 호출을 트랜잭션 밖으로 분리하여 DB 커넥션 풀 고갈을 방지하되, 낙관적 락으로 일관성을 보장한다"고 명시하면 아키텍처 판단력을 보여줄 수 있다.

---

## 3. 동시성 전략 평가

### 3.1 계획서 채택: 낙관적 락 (`@Version`)

계획서(라인 69): `Member.version` 필드에 `@Version` 어노테이션. 충돌 시 `OptimisticLockException` → 409 Conflict.

### 3.2 대안 비교

| 전략 | 동작 | 장점 | 단점 | 적합도 |
|------|------|------|------|--------|
| **낙관적 락(@Version)** | UPDATE WHERE version=? → affected_rows=0이면 예외 | 락 대기 없음, 처리량 높음, 구현 단순 | 충돌 시 재시도 부담을 클라이언트에 전가 | **적합.** 동일 회원 동시 요청은 드문 시나리오 |
| **비관적 락(SELECT FOR UPDATE)** | 행 수준 배타적 락 | 충돌 시 대기 후 처리, 재시도 불필요 | 데드락 위험, 외부 호출 중 락 보유 시 커넥션 풀 고갈 악화 | 현재 All-in-One TX와 결합 시 **위험** |
| **Redis 분산 락** | Redisson/Lettuce로 회원 단위 락 | DB 부하 분산, 분산 환경 대응 | 인프라 추가(Redis), 과제 범위 과잉 | 과제에 과도 |
| **Single-Writer per Member** | 회원별 큐/스트라이프 락 | 직렬 보장 | JVM 단일 인스턴스 가정, 스케일아웃 불가 | 과제 규모에 부적합 |

### 3.3 시나리오: 구독+해지 동시 도착

```
Thread A: SUBSCRIBE(BASIC→PREMIUM), Member.version=1
Thread B: UNSUBSCRIBE(BASIC→NONE), Member.version=1

2-Phase 방식 가정:
  A-Phase1: 읽기 (version=1, status=BASIC) → 전이 검증 통과
  B-Phase1: 읽기 (version=1, status=BASIC) → 전이 검증 통과
  A-Phase2: csrng 호출 → random=1
  B-Phase2: csrng 호출 → random=1
  A-Phase3: UPDATE SET status=PREMIUM, version=2 WHERE id=? AND version=1 → 성공
  B-Phase3: UPDATE SET status=NONE, version=2 WHERE id=? AND version=1 → affected_rows=0 → OptimisticLockException → 409
```

결과: A가 선착, B는 409로 거부. 클라이언트가 재시도하면 version=2, status=PREMIUM에서 UNSUBSCRIBE(PREMIUM→NONE) 검증 통과하여 정상 처리. **정합성 보장됨.**

### 3.4 ABA 문제

낙관적 락에서 ABA 문제는 version이 단조 증가(auto-increment)이므로 발생하지 않는다. `@Version`은 JPA가 `version = version + 1`로 갱신하므로 A→B→A로 값이 돌아오는 경우가 없다. **ABA 문제 없음.**

### 3.5 OptimisticLockException → 409 사용자 경험

409 Conflict는 의미적으로 적절하다. 응답 본문에 `"message": "다른 요청과 충돌이 발생했습니다. 잠시 후 다시 시도해 주세요."`와 `Retry-After: 1` 헤더를 포함하면 클라이언트 경험이 개선된다.

**권고:** 낙관적 락 채택을 **endorse**한다. 단, 2-Phase 트랜잭션 분리와 결합해야 비관적 락의 장기 점유 문제를 회피할 수 있다.

---

## 4. 외부 API 격리 (csrng + Gemini)

### 4.1 Resilience4j 어노테이션 적용 순서

계획서(라인 188): `@Retry → @CircuitBreaker → @TimeLimiter` (외부 → 내부).

Resilience4j Spring Boot 통합에서 어노테이션 적용 순서(aspect order)는 기본값:
- `TimeLimiter` order = 0 (가장 바깥)
- `CircuitBreaker` order = 1
- `Retry` order = 2 (가장 안쪽)

즉, 실제 실행 순서는 `TimeLimiter → CircuitBreaker → Retry → 실제 호출`이다. 계획서가 기술한 "Retry → CircuitBreaker → TimeLimiter"는 **어노테이션 나열 순서**이지 **실행 순서**가 아니다. 실행 순서는 Spring aspect order에 의해 결정된다.

**문제:** 기본 순서대로면 TimeLimiter가 전체(Retry 포함)를 감싸므로, 3회 재시도 전체가 2초 안에 완료되어야 한다. 이는 의도와 다를 수 있다. 개별 호출에 2초 제한을 걸고 싶다면 Retry가 가장 바깥이어야 한다.

**권고:** `application.yml`에서 aspect order를 명시적으로 설정:
```yaml
resilience4j:
  retry:
    retry-aspect-order: 3    # 가장 바깥
  circuitbreaker:
    circuit-breaker-aspect-order: 2
  timelimiter:
    time-limiter-aspect-order: 1  # 가장 안쪽
```

이렇게 하면 실행 순서가 `Retry → CircuitBreaker → TimeLimiter → 실제 호출`이 되어, 각 시도마다 2초 제한이 걸리고, 실패 시 재시도하며, 실패 누적이 서킷브레이커에 반영된다.

### 4.2 TimeLimiter + 동기 RestClient 호환성

**이것은 Critical 결함이다.**

Resilience4j의 `@TimeLimiter`는 `CompletableFuture<T>` 또는 Reactor의 `Mono<T>`/`Flux<T>`를 반환하는 메서드에서만 동작한다. **동기 메서드(일반 반환 타입)에서는 `@TimeLimiter`가 적용되지 않는다.**

계획서는 Spring 6 RestClient(동기)를 사용하므로, `CsrngClient`의 메서드 시그니처가 `CsrngResponse callCsrng()`처럼 동기 반환이면 `@TimeLimiter`는 **무시된다.**

**해결 방안:**

| 방안 | 설명 | 복잡도 |
|------|------|--------|
| **(A) RestClient 연결/읽기 타임아웃 직접 설정** | `RestClient.builder().requestFactory(...)` 에서 `SimpleClientHttpRequestFactory.setConnectTimeout(2000)`, `setReadTimeout(2000)` 설정. TimeLimiter 어노테이션 제거. | 낮음 |
| **(B) CompletableFuture 래핑** | 메서드 반환을 `CompletableFuture<CsrngResponse>`로 변경, 내부에서 `CompletableFuture.supplyAsync(() -> restClient.get()...)`. | 중간 |
| **(C) WebClient(Reactive)로 전환** | `Mono<CsrngResponse>` 반환. TimeLimiter 정상 동작. | 높음 (동기 코드 전체 재설계) |

**권고:** 방안 (A)를 채택. RestClient의 HTTP 타임아웃으로 개별 호출 시간을 제한하고, `@TimeLimiter` 어노테이션은 제거. 이것이 가장 단순하고 동기 코드와 정합적이다. `@Retry`와 `@CircuitBreaker`는 동기 메서드에서도 정상 동작한다.

### 4.3 csrng 응답 파싱

csrng 응답 형식: `[{"status":"success","min":0,"max":1,"random":1}]` — JSON 배열.

**고려사항:**
- 빈 배열 `[]` 반환 시: `IndexOutOfBoundsException` 발생 가능. 방어 코드 필요.
- `status != "success"` 시: 계획서(라인 190)에서 실패 처리 명시. **커버됨.**
- JSON 파싱 실패 (비정상 응답): `RestClientException` 계열이 발생하여 Retry 대상. **커버됨.**
- 배열 내 다중 요소: 첫 번째 요소만 사용하는 것이 합리적. 명시 필요.

**권고:** 빈 배열 또는 null 응답에 대한 방어 로직을 명시. `response == null || response.isEmpty()` → `CsrngUnavailableException`.

### 4.4 random=0 vs HTTP 5xx 구분

| 상황 | 성격 | 계획서 매핑 | 적절성 |
|------|------|------------|--------|
| `random=0` | 비즈니스 규칙에 의한 거부 (csrng가 정상 응답) | 409 `CSRNG_REJECTED` | **부적절.** 아래 섹션 5에서 상세 분석 |
| HTTP 5xx | 인프라 장애 | 502 `EXTERNAL_API_UNAVAILABLE` | **적절** |
| Timeout | 인프라 장애 | 502 | **적절** |
| CB Open | 인프라 장애(누적) | 502 | **적절** |

### 4.5 LLM 별도 Resilience4j 인스턴스

계획서(라인 214): `llm` 인스턴스를 csrng와 분리. Retry 2회, Timeout 3s, Fallback=null.

**Endorse.** csrng와 LLM은 완전히 다른 SLA와 장애 특성을 가진다. 서킷브레이커 상태가 서로 영향을 주면 안 된다. 분리가 정확하다.

단, LLM에도 RestClient 타임아웃 직접 설정(섹션 4.2 방안 A)이 동일하게 적용되어야 한다. Gemini 호출은 응답이 느릴 수 있으므로 `readTimeout=3000ms`를 별도 RestClient 인스턴스에 설정.

---

## 5. HTTP 상태 코드 매핑 시맨틱 검증

### 5.1 422 Unprocessable Entity

계획서(라인 141): 채널 권한 없음, 잘못된 상태 전이 → 422.

| 후보 | RFC 의미 | 적합성 |
|------|---------|--------|
| 400 Bad Request | 요청 문법 오류 | 문법은 올바르고 의미가 틀린 경우에 부적합 |
| 422 Unprocessable Entity | 문법은 올바르나 의미적으로 처리 불가 (RFC 4918) | **적합.** "채널이 구독을 지원하지 않음", "PREMIUM에서 구독 불가"는 의미적 오류 |
| 409 Conflict | 현재 리소스 상태와 충돌 | 상태 전이 불가를 충돌로 볼 수도 있으나, 409는 보통 동시성 충돌에 사용 |

**Endorse.** 422가 가장 정확하다. 400은 입력 검증(`@Valid`) 실패에 예약하고, 422는 도메인 규칙 위반에 사용하는 것이 일관적이다.

### 5.2 409 Conflict: csrng random=0

계획서(라인 191): `random=0` → `CsrngRejectedException` → 409 `CSRNG_REJECTED`.

**이것은 의미적으로 부정확하다.**

`random=0`은 외부 API가 정상 응답(HTTP 200)을 반환했으나 비즈니스 로직상 거부된 케이스다. 이것은:
- 409 Conflict가 아니다. 리소스 상태 충돌이 아님.
- 502 Bad Gateway도 아니다. 외부 API가 정상 동작했음.

| 후보 | 적합성 |
|------|--------|
| 409 | **부적합.** 동시성 충돌(OptimisticLock)과 의미가 겹침 |
| 502 | **부적합.** 외부 API 장애가 아님 |
| 422 | **후보.** "외부 검증에 의해 처리 불가"로 해석 가능 |
| 503 Service Unavailable | **부적합.** 서비스 자체는 가용 |
| **200 + 실패 본문** | 과제 원문이 "예외 발생 → 트랜잭션 롤백"을 명시하므로 2xx는 부적합 |

**권고:** `random=0`은 422 Unprocessable Entity로 매핑하되, 에러 코드를 `EXTERNAL_VALIDATION_REJECTED`로 구분. 이렇게 하면:
- 409는 낙관락 충돌 전용
- 422는 도메인 규칙 위반 + 외부 검증 거부
- 502는 외부 API 인프라 장애 전용

에러 응답 본문의 `code` 필드로 422 내의 세부 원인을 구분할 수 있다.

### 5.3 RFC 7807 (application/problem+json)

계획서에 RFC 7807 언급이 없다. Spring Boot 3는 `ProblemDetail`을 기본 지원한다.

**권고:** `GlobalExceptionHandler`에서 `ProblemDetail`을 반환 타입으로 사용. 구현 비용이 매우 낮고(Spring Boot 3 내장), 평가자에게 표준 준수를 보여줄 수 있다. `spring.mvc.problemdetails.enabled=true` 설정만으로 기본 활성화.

---

## 6. 도메인 패키지 구조 평가

### 6.1 도메인 중심 vs 레이어드

계획서(라인 76-108): 도메인 중심 패키지 (`member`, `channel`, `subscription`, `history`, `external`).

| 옵션 | 장점 | 단점 |
|------|------|------|
| 도메인 중심(현재) | 응집도 높음, 도메인 경계 명확, 모듈화 용이 | 소규모 프로젝트에서는 과분할 가능 |
| 레이어드(controller/service/repository) | 익숙한 구조, Spring 가이드 기본 | 도메인 간 의존성 관리 어려움 |

**Endorse.** 과제 규모(엔티티 3개)에 도메인 중심이 약간 과분할이지만, 5~8년 차 시니어 과제에서 아키텍처 판단력을 보여주는 데 적합하다.

### 6.2 common 패키지 비대 우려

현재 `common` 하위: `exception`, `response`, `config`. 3개 하위 패키지.

현재 규모에서는 적절하다. 단, `WebConfig`, `OpenApiConfig`, `Resilience4jConfig`를 모두 `common.config`에 넣으면 설정의 도메인 귀속이 불명확해진다.

**권고:** Resilience4j 설정은 `external` 패키지로 이동. `common.config`에는 `WebConfig`, `OpenApiConfig` 등 애플리케이션 전역 설정만 유지. 이렇게 하면 `common`의 책임이 명확해진다.

### 6.3 subscription과 history 분리

| 옵션 | 장점 | 단점 |
|------|------|------|
| `subscription` + `history` 별도 패키지(현재) | 독립 조회 API 존재(`/subscription-histories`), 관심사 분리 명확 | `SubscriptionService`가 `HistoryService`에 의존(이력 적재) |
| `subscription.history` 서브패키지 | 이력이 구독의 하위 개념임을 구조로 표현 | API 컨트롤러가 다른 리소스 경로(`/members/{id}/subscription-histories`)를 가져 패키지와 불일치 |

**Endorse.** 별도 패키지 유지. 이력 조회 API가 `members/{phoneNumber}` 하위 리소스로 설계되어 있고, LLM 요약이라는 독립적 부가 기능이 있으므로 별도 패키지가 응집도 면에서 우수하다.

---

## 7. 추가 아키텍처 고려사항

### 7.1 ENUM DB 저장 방식

계획서(라인 309): "DB는 VARCHAR + CHECK, JPA는 `@Enumerated(STRING)`".

**Endorse.** MySQL ENUM 타입은 스키마 변경 시 `ALTER TABLE ... MODIFY COLUMN`이 필요하고, 내부적으로 정수 인덱싱이어서 순서 의존성이 있다. VARCHAR + `@Enumerated(EnumType.STRING)`이 마이그레이션 안전성과 가독성 모두 우수하다.

단, MySQL의 CHECK constraint는 8.0.16 이상에서만 실제 강제된다(이전 버전은 파싱만 하고 무시). Flyway 마이그레이션에서 MySQL 버전을 8.0.16+ 이상으로 명시하거나, 애플리케이션 레벨에서도 검증 필요. 계획서가 MySQL 8.x를 명시하므로 **커버됨.**

### 7.2 Virtual Thread + HikariCP 상호작용

Java 21 Virtual Thread 활성화 시(`spring.threads.virtual.enabled=true`), 모든 요청이 VT에서 처리된다. VT는 수천 개가 동시 존재할 수 있으나, HikariCP의 `maximumPoolSize`(기본 10)는 변하지 않는다.

**위험:** VT 환경에서 동시 요청 100개가 도달하면 90개는 HikariCP에서 커넥션 대기. VT의 장점(높은 동시성)이 JDBC 커넥션 풀에서 병목이 된다.

**권고:**
1. `maximumPoolSize`를 적절히 조정 (20~50, DB 서버 `max_connections` 대비).
2. 섹션 2.4의 2-Phase 접근으로 커넥션 점유 시간 최소화.
3. HikariCP의 `connectionTimeout`을 5초로 단축하여 빠른 실패(fast-fail) 유도.
4. Semaphore로 동시 DB 접근 수를 커넥션 풀 크기로 제한하는 것은 과제 범위 밖.

### 7.3 Testcontainers + Flyway 부팅 시간

Testcontainers MySQL 컨테이너 초기 부팅: 약 5~15초. Flyway 마이그레이션 추가 시 +1~2초.

**권고:**
- `@Testcontainers` + `static` 컨테이너로 테스트 클래스 간 컨테이너 재사용.
- `@DynamicPropertySource`로 연결 정보 주입.
- `spring.flyway.clean-disabled=false`는 테스트 환경에서만 활성화하여 각 테스트 전 클린 마이그레이션.
- 계획서에 이 부분이 명시되지 않았으나, 구현 시 자연스럽게 처리 가능. **Minor.**

### 7.4 이력 조회 페이징 부재

계획서(라인 259): "페이징 없이 시간 ASC".

**위험:** 한 회원이 구독/해지를 수백 회 반복하면 이력이 대량 누적된다. 페이징 없이 전체 조회 시:
1. 응답 크기 증가 (네트워크 부담)
2. LLM 프롬프트에 전체 이력 포함 시 토큰 초과 또는 비용 증가
3. DB 쿼리 성능 저하 (인덱스가 `(memberId, actedAt)`이므로 쿼리 자체는 효율적이나 결과 셋이 큼)

**권고:**
- 과제 원문이 페이징을 요구하지 않으므로 전체 조회 유지는 수용 가능.
- 단, LLM 프롬프트에는 **최근 N건(예: 20건)**만 포함하여 토큰 비용 제한. 전체 이력은 `history` 배열로 반환.
- README에 "운영 환경에서는 Cursor-based Pagination 도입 권장"을 언급하면 설계 판단력을 보여줄 수 있다.

### 7.5 최초 회원 자동 생성 전략

과제 원문(라인 69): "최초 회원은 ... 어떤 상태로든 가입할 수 있습니다."

계획서 Step 4 수용 기준: "신규 회원 → PREMIUM 구독 성공 (Member 생성 + 이력 1건)".

**고려사항:** 구독 API가 `phoneNumber`로 요청을 받으므로, 존재하지 않는 번호면 `Member`를 자동 생성해야 한다. 이때:
- `findByPhoneNumber` → null → 새 `Member` 생성 + 구독 처리를 **하나의 트랜잭션**에서.
- 동시에 같은 번호로 2개 요청이 오면 `UNIQUE` 제약 위반 가능.
- **권고:** `phoneNumber`에 UNIQUE 인덱스가 있으므로, 중복 `INSERT` 시 `DataIntegrityViolationException` → 409 또는 재시도 로직. 계획서에 이 시나리오 처리가 명시되지 않았다.

---

## 8. 우선순위별 권고

### Critical (Phase 1 진입 전 필수 수정)

| # | 항목 | 설명 |
|---|------|------|
| C-1 | **TimeLimiter 동기 호환성** | `@TimeLimiter`는 동기 RestClient 메서드에서 동작하지 않음. RestClient의 HTTP 타임아웃(connectTimeout/readTimeout)으로 대체하고 `@TimeLimiter` 어노테이션 제거. (섹션 4.2) |
| C-2 | **트랜잭션 경계 재설계** | 외부 API 호출을 `@Transactional` 밖으로 분리(2-Phase). DB 커넥션 최악 6.6초 점유 → 풀 고갈 방지. (섹션 2.4) |
| C-3 | **Resilience4j aspect order 명시** | 기본 순서(TimeLimiter 최외곽)가 의도와 다름. `retry-aspect-order`를 최외곽으로 설정. (섹션 4.1) |

### Major (Phase 2~4 진입 전 수정)

| # | 항목 | 설명 |
|---|------|------|
| M-1 | **random=0 HTTP 상태 코드** | 409에서 422로 변경. 409는 낙관락 충돌 전용으로 예약. (섹션 5.2) |
| M-2 | **StateTransitionPolicy 단일 책임** | enum `canTransitionTo`와 `StateTransitionPolicy`의 이중 구조 정리. Policy를 single source of truth로. (섹션 1.4) |
| M-3 | **csrng 빈 배열 방어** | `[]` 응답 시 `IndexOutOfBoundsException` 방지 로직 추가. (섹션 4.3) |
| M-4 | **최초 회원 동시 생성 처리** | 동일 `phoneNumber` 동시 구독 요청 시 UNIQUE 위반 예외 처리 추가. (섹션 7.5) |
| M-5 | **LLM 토큰 제한** | 이력 전체를 프롬프트에 넣지 않고 최근 N건으로 제한. (섹션 7.4) |
| M-6 | **RFC 7807 ProblemDetail 도입** | Spring Boot 3 내장 `ProblemDetail` 활용. 구현 비용 최소. (섹션 5.3) |

### Minor (시간 여유 시)

| # | 항목 | 설명 |
|---|------|------|
| m-1 | **Resilience4j 설정을 external 패키지로 이동** | `common.config` 비대 방지. (섹션 6.2) |
| m-2 | **Testcontainers static 컨테이너 재사용** | 테스트 부팅 시간 절감. (섹션 7.3) |
| m-3 | **HikariCP 튜닝 명시** | `maximumPoolSize`, `connectionTimeout` 설정을 `application.yml`에 명시. (섹션 7.2) |
| m-4 | **이력 조회 페이징 README 언급** | 운영 환경 페이징 권장 문서화. (섹션 7.4) |
| m-5 | **targetStatus=NONE으로 구독 요청 방어** | 입력 검증에서 구독 시 NONE 거부 추가. (섹션 1.3) |
| m-6 | **Retry-After 헤더** | 409 응답에 `Retry-After: 1` 헤더 추가. (섹션 3.5) |

---

## 9. 메타

- **검토자:** oh-my-claudecode:architect (opus, READ-ONLY)
- **검토일:** 2026-05-19
- **입력 문서:** ASSIGNMENT.md, 계획서(2026-05-19-artinus-subscription-plan.md), README.md
- **권고 수:** Critical 3건, Major 6건, Minor 6건
