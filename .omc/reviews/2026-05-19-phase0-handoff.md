# Phase 0 Handoff — ARTINUS 구독 서비스 계획서 검증 통합 인계 문서

**작성일:** 2026-05-19
**작성자:** main session (critic + architect 통합)
**대상 독자:** Phase 1~6 본 ralph 루프
**입력 리포트:**
- `.omc/reviews/2026-05-19-phase0-critic.md` (170줄, CRITICAL 0 / MAJOR 5 / MINOR 5)
- `.omc/reviews/2026-05-19-phase0-architect.md` (402줄, Critical 3 / Major 6 / Minor 6)
**검토 대상 계획서:** `.omc/plans/2026-05-19-artinus-subscription-plan.md`

---

## 0. TL;DR — Phase 1 진입 판단

**판단: Conditional Approval (조건부 승인)**

본 계획서는 9개 요구사항을 정확히 매핑하고 도메인/상태머신/Resilience 설정을 구체적으로 정의한 견고한 출발점이다. 다만 **3건의 Critical 결함**(architect 식별)이 구현 단계에서 즉시 표면화되므로, 계획서 정정을 동반한 진입이 필요하다. critic은 MAJOR로 분류한 2건이 architect의 Critical과 동일 또는 인접하므로 두 검토자의 신호가 일치한다.

**필수 선행 조치 (Phase 1 진입 전):**
1. `@TimeLimiter` 동기 RestClient 비호환 문제 해결 — RestClient 자체 타임아웃으로 대체
2. 트랜잭션 경계 2-Phase 재설계 — DB 커넥션 풀 고갈 방지
3. csrng `random=0` HTTP 상태 코드 422로 통일 (502/409 혼재 해소)
4. Resilience4j aspect order를 application.yml에 명시

위 4건은 본 문서 §3 `plan_amendments`에 before/after로 정리되어 있다. Phase 1 부트스트랩에서 코드 변경 없이 계획서 패치만 선행 적용하면 Phase 2 이후 모든 단계가 안전하게 진행된다.

---

## 1. 두 검토자의 공통 지적 (HIGH CONFIDENCE)

두 검토자가 독립적으로 같은 결함을 식별한 항목은 신뢰도가 가장 높다. **반드시 수용한다.**

### 1.1 트랜잭션 안에서 외부 HTTP 호출 — 커넥션 풀 고갈

| 검토자 | 분류 | 세부 |
|---|---|---|
| critic | MAJOR (§2.2 첫번째) | 최악 9초 점유, HikariCP 풀(10) 동시 10요청에서 전체 풀 고갈 |
| architect | **Critical C-2** (§2.2~§2.4) | 최악 6.6초 × 동시 10요청 = 66초 풀 점유, VT 환경에서 위험 증폭 |

**합의된 해결:** 2-Phase 트랜잭션 분리 (architect §2.4 권장안 채택)
```
Phase 1 (Read-only 짧은 TX 또는 TX 밖): Member 조회 + version 기록, Channel 권한, 상태 전이 검증
Phase 2 (TX 밖): csrng 호출 (Resilience4j 적용), random=0이면 즉시 예외 → DB 미접근
Phase 3 (짧은 Write TX): Member 재조회 + version 검증(낙관락), 상태 변경, 이력 적재
```

낙관적 락(`@Version`)이 Phase 1~3 사이 동시 변경을 안전하게 감지하므로, 일관성도 보장된다.

### 1.2 csrng `random=0` HTTP 상태 코드 부적합 (502/409 혼재)

| 검토자 | 분류 | 세부 |
|---|---|---|
| critic | MAJOR (§2.2 두번째 + §2.3 첫번째) | 계획서 §4.1은 502, §5는 409로 상충. 둘 다 HTTP 시맨틱 부적합 |
| architect | **Major M-1** (§5.2) | random=0은 외부 API 정상 응답이므로 502 아님. 409는 낙관락 충돌과 충돌 |

**합의된 해결:**
- random=0 (외부 검증 거부): **422 Unprocessable Entity** + 에러코드 `EXTERNAL_VALIDATION_REJECTED`
- csrng 인프라 장애(timeout/5xx/CB Open): **502 Bad Gateway** + 에러코드 `EXTERNAL_API_UNAVAILABLE`
- 낙관락 충돌: **409 Conflict** + 에러코드 `OPTIMISTIC_LOCK_CONFLICT` (Retry-After: 1)
- 도메인 규칙 위반(채널 권한/잘못된 전이): **422** + 에러코드 `INVALID_STATE_TRANSITION` / `CHANNEL_NOT_ALLOWED`

422 내부의 세부 원인은 응답 본문 `code` 필드로 구분.

### 1.3 LLM 요약 — PII/토큰 보호

| 검토자 | 분류 | 세부 |
|---|---|---|
| critic | MAJOR (§2.2 세번째) | 프롬프트에 phoneNumber 포함 가능성, 마스킹 정책 미정의 |
| architect | Major M-5 (§7.4) | 이력 전체 vs 최근 N건 토큰 비용, 페이징 부재 |

**합의된 해결:**
- 프롬프트에 `phoneNumber`는 **포함하지 않는다**. history_lines 구성 필드: `(actedAt, channelName, actionType, newStatus)` 4개만.
- 이력이 N건 초과 시 **최근 20건만** 프롬프트에 포함 (전체 이력은 응답의 `history` 배열에 그대로 반환).
- README에 "운영 환경 Cursor-based Pagination 권장" 메모.

### 1.4 RFC 7807 ProblemDetail

| 검토자 | 분류 | 세부 |
|---|---|---|
| critic | MINOR (§2.3 세번째) | 시니어 과제 가점 요소, Spring Boot 3 내장 |
| architect | Major M-6 (§5.3) | 구현 비용 매우 낮음, `spring.mvc.problemdetails.enabled=true` |

**합의된 해결:** `GlobalExceptionHandler`에서 `ProblemDetail`을 반환 타입으로 사용. application.yml에 `spring.mvc.problemdetails.enabled=true`.

---

## 2. 단독 지적 사항

### 2.1 architect 단독 (Critical/Major)

| ID | 항목 | 채택 여부 | 사유 |
|---|---|---|---|
| **C-1** | `@TimeLimiter`는 동기 RestClient에서 동작하지 않음. RestClient `connectTimeout`/`readTimeout`(2000ms)로 대체, `@TimeLimiter` 어노테이션 제거 | **채택 (필수)** | Resilience4j 공식 문서 일치. 코드가 작성된 후에야 발견되면 큰 재작업. |
| **C-3** | Resilience4j aspect order 명시 (Retry 최외곽, TimeLimiter 최내곽) | **부분 채택** | C-1으로 `@TimeLimiter` 제거 시 적용 대상은 `@Retry`+`@CircuitBreaker`만. Retry 최외곽으로 설정 유지 |
| M-2 | `StateTransitionPolicy` single source of truth (enum에 전이 로직 두지 않음) | **채택** | enum 내부 메서드와 별도 Policy 클래스 이중 정의 시 SoT 혼란. Policy가 (action, currentStatus, targetStatus, channel) 4-tuple 검증 |
| M-3 | csrng 빈 배열 `[]` 응답 방어 | **채택** | `response == null || response.isEmpty()` → `CsrngUnavailableException` (Retry 대상) |
| M-4 | 최초 회원 동시 생성 시 UNIQUE 위반 처리 | **채택** | `findByPhoneNumber` null → INSERT 시점 동시성. `DataIntegrityViolationException` 캐치 후 재조회 또는 409 |

### 2.2 critic 단독 (MAJOR/MINOR)

| 항목 | 채택 여부 | 사유 |
|---|---|---|
| AWS NAT Gateway / VPC Endpoint / KMS 명시 (MAJOR) | **채택** | Step 7 작성 시 반영. private subnet → 외부 API 경로 설명 필수 |
| E.164 정규화 정책 모호 (MAJOR) | **조건부 채택** | 과제 맥락상 국내 번호만 가정. **국내 형식 `01012345678`(11자리)로 저장** 결정. E.164 표현은 README에서 삭제 또는 "국내 번호 정규화"로 표현 변경 |
| 동일 상태 재요청 idempotency 명시 (MINOR) | **채택** | StateTransitionPolicy에 "동일 상태 전이 불허(422)" 규칙 한 줄 추가. 테스트 케이스에 명시 |
| pre-commit hook (detect-secrets) (MINOR) | **기각** | 1인 과제 + Step 8 제출 전 수동 grep 체크리스트로 대체. 도입 비용 대비 효익 낮음 |
| commitlint/husky (MINOR) | **기각** | 동일 사유. 본인 규율로 충당 |
| JaCoCo 90% 목표 현실성 (MINOR) | **수용 (수정)** | Gate는 70%, 도메인/서비스 90%는 **권장 목표**로 명시. 시간 압박 시 핵심 시나리오 우선 |

### 2.3 차이/상충 의견 — 없음

두 검토자의 지적이 상충하는 항목은 없다. critic은 비즈니스/운영 관점, architect는 기술 메커니즘 관점에서 각자 상보적으로 다른 영역을 커버했다.

---

## 3. 계획서 수정 사항 (`plan_amendments`)

Phase 1 진입 전 계획서(`.omc/plans/2026-05-19-artinus-subscription-plan.md`)에 다음 패치를 적용한다. **본 인계 문서로 의도를 고정**하므로, Phase 1 워커가 계획서 자체를 수정하지 않고 본 문서를 우선 참조해도 무방.

### 3.1 §5 트랜잭션 경계 — 2-Phase 재설계

**Before:**
> `@Transactional` 메서드 내에서 외부 API 호출 → 응답이 실패면 `RuntimeException` throw → 자동 롤백.
> ...상태 검증/락 → 외부 호출 → 상태 변경 → 이력 적재 순서를 한 트랜잭션으로 묶고, `timeout-duration` 2초 보장.

**After:**
```
2-Phase 트랜잭션 분리:
  Phase A (Read-only, no @Transactional 또는 짧은 readOnly TX):
    - Member 조회 (없으면 Phase C에서 생성 분기)
    - Channel 권한 검증
    - StateTransitionPolicy.validate(action, currentStatus, targetStatus, channel)
    - 통과 시 member.version 보관

  Phase B (No TX):
    - csrng 외부 호출 (Resilience4j 적용)
    - random=0 또는 status!=success → 즉시 예외 throw, Phase C 미진입
    - 인프라 장애 → 502, Phase C 미진입

  Phase C (Write @Transactional):
    - Member 재조회 + version 비교 (낙관락)
    - 신규 회원이면 INSERT (UNIQUE 위반 시 catch → 재조회 1회)
    - 상태 변경
    - SubscriptionHistory 적재
    - 커밋

근거: 외부 HTTP 호출이 TX 안에 있으면 최악 6.6초 DB 커넥션 점유. 동시 요청 10건만으로
HikariCP 풀(기본 10) 고갈. VT 환경에서 위험 증폭. 2-Phase는 DB 커넥션 점유를 Phase C의 수~수십 ms로 제한.
```

### 3.2 §4 HTTP 상태 코드 매핑 통일

**Before (§4.1):** `502: csrng random=0(롤백) 또는 fallback 결과` / `409: 낙관락 충돌`
**Before (§5):** `random=0 → 409 CSRNG_REJECTED`

**After (통일 매트릭스):**

| 상황 | HTTP | code | 비고 |
|---|---|---|---|
| 입력 문법 오류 (@Valid 실패) | 400 | `VALIDATION_FAILED` | Bean Validation |
| 채널 권한 없음 | 422 | `CHANNEL_NOT_ALLOWED` | 도메인 규칙 |
| 잘못된 상태 전이 | 422 | `INVALID_STATE_TRANSITION` | 도메인 규칙 |
| csrng `random=0` | **422** | `EXTERNAL_VALIDATION_REJECTED` | 외부 검증 거부 |
| 낙관락 충돌 | 409 | `OPTIMISTIC_LOCK_CONFLICT` | + `Retry-After: 1` |
| 최초 회원 UNIQUE 동시 충돌 | 409 | `MEMBER_CREATION_CONFLICT` | 재시도 가이드 |
| csrng 타임아웃/5xx/CB Open | 502 | `EXTERNAL_API_UNAVAILABLE` | 인프라 장애 |
| 회원 미존재 (조회 API) | 404 | `MEMBER_NOT_FOUND` | 이력 조회만 |
| 서버 내부 오류 | 500 | `INTERNAL_ERROR` | 미분류 |

응답 본문은 RFC 7807 `application/problem+json` 사용 (`spring.mvc.problemdetails.enabled=true`).

### 3.3 §3 외부 API 클라이언트 어노테이션

**Before:** `@Retry`, `@CircuitBreaker`, `@TimeLimiter`

**After:**
- `@Retry(name="csrng")` + `@CircuitBreaker(name="csrng")` **만** 사용 (동기 RestClient).
- `@TimeLimiter` **사용하지 않음** (동기 메서드 비호환).
- 타임아웃은 RestClient 자체 설정으로:
  ```java
  ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
      .withConnectTimeout(Duration.ofMillis(1000))
      .withReadTimeout(Duration.ofMillis(2000));
  // 또는 SimpleClientHttpRequestFactory + setConnectTimeout/setReadTimeout
  ```
- `application.yml`에 aspect order 명시:
  ```yaml
  resilience4j:
    retry:
      retry-aspect-order: 2     # 외곽 (먼저 적용)
    circuitbreaker:
      circuit-breaker-aspect-order: 1   # 내곽
  ```
- LLM 클라이언트도 동일 패턴 (별도 RestClient 인스턴스, readTimeout=3000ms, `@Retry(name="llm")`).

### 3.4 §2 도메인 모델 — phoneNumber 정규화 정책

**Before:** `phoneNumber (UNIQUE, VARCHAR(20)) -- E.164 정규화`

**After:**
```
phoneNumber (UNIQUE, VARCHAR(11)) -- 국내 형식 정규화

정규화 규칙:
  입력: "010-1234-5678" | "010 1234 5678" | "(010)1234-5678" | "01012345678"
  처리: 모든 비숫자 문자 제거 → 11자리 검증 → "01012345678"로 저장
  검증: ^010\d{8}$ (010으로 시작하는 11자리 숫자)
  검증 실패 → 400 VALIDATION_FAILED

근거: 과제 예시가 "010-1234-5678" 한국 형식. E.164(+82) 변환은 과제 응답 형식과 어긋날 수 있음.
국제 번호 확장은 운영 단계 요건으로 분리.
```

### 3.5 §2 상태 머신 — Idempotency 명시

**Before:** 상태 전이 매트릭스만 표기, 동일 상태 처리 암묵적

**After:** 매트릭스 하단에 추가:
```
- 동일 상태로의 전이 요청(SUBSCRIBE BASIC→BASIC, UNSUBSCRIBE NONE→NONE 등)은
  허용 집합에 포함되지 않으므로 422 INVALID_STATE_TRANSITION 응답.
- targetStatus=NONE으로 구독 요청(SUBSCRIBE)은 의미 없는 요청이므로 입력 검증 단계에서 거부.
- 신규 회원(DB 미존재)이 NONE으로 구독 요청도 거부 (Member 생성하지 않음).
```

### 3.6 §6 LLM 프롬프트 — PII/토큰 보호

**Before:** 프롬프트 템플릿에 history_lines만 명시

**After:**
```
history_lines 구성 시 포함 필드: actedAt(yyyy-MM-dd), channelName, actionType, newStatus
  → phoneNumber 미포함 (PII 보호)
  → 이력 N건 초과 시 최근 20건만 포함 (Gemini 토큰 비용 제한)
응답 외부 노출 시에도 phoneNumber는 요청 경로 파라미터 기반이므로 별도 노출 없음.
```

### 3.7 §3 패키지 구조 미세 조정

**After 추가:**
```
common.config         # WebConfig, OpenApiConfig (애플리케이션 전역만)
external.config       # Resilience4jConfig (csrng/llm 인스턴스), RestClientConfig
```
Resilience4j 설정은 `external` 패키지로 이동하여 책임 응집.

### 3.8 §7 AWS 아키텍처 — 누락 항목 보강

Step 7 작성 시 다음 항목을 반드시 포함:
- NAT Gateway (Multi-AZ 2개) 또는 VPC Endpoint(S3/ECR/Secrets Manager) 선택 트레이드오프 + 월 비용 추정
- KMS CMK로 RDS 스토리지/Secrets Manager 암호화 (envelope encryption)
- ECR Pull 트래픽용 VPC Endpoint (NAT 비용 절감)
- DR: RPO < 5분 (RDS 자동 백업), RTO < 15분 (Multi-AZ failover)
- ALB → ECS Task 보안그룹 체인 (least privilege)
- IAM Task Role (Secrets Manager 읽기 권한만, 최소권한)
- WAF Managed Rules (AWS Managed Rules Core Rule Set)
- CloudWatch 알람: CircuitBreaker open metric, 5xx rate, csrng latency p99

### 3.9 §8 수용 기준 — 보강

추가 케이스:
- StateTransitionPolicy 단위 테스트: 12개 전이 + 6개 거부(동일 상태/금지 전이) = **18 케이스**
- csrng 빈 배열 응답 시 502 응답 검증
- 동일 phoneNumber 동시 신규 가입 요청 (2 thread) → 1건 성공, 1건 409
- ProblemDetail 응답 본문 구조 검증 (type/title/status/detail/code/instance)

---

## 4. Blockers — Phase 1 진입 전 반드시 처리

코드 작성 전 확실히 결정/수정되어야 하는 항목.

| # | 항목 | 처리 방식 |
|---|---|---|
| B-1 | 위 §3.1~§3.9 amendments를 본 문서로 고정 | 본 인계 문서를 Phase 1 워커가 계획서와 함께 읽음 |
| B-2 | 계획서 파일 자체를 패치할지 결정 | **권장: 계획서는 보존(history), 본 인계 문서가 정정안의 SoT** |
| B-3 | Gemini API Key 보유 여부 | 사용자 환경 변수로 주입. 없으면 LLM은 mock/stub으로 우회. 평가자도 키가 없을 수 있으므로 application-mock 프로파일로 우회 가능하게 구성 |
| B-4 | MySQL 버전 확정 (8.0.16+ 필수) | docker-compose / Testcontainers 이미지 태그 명시 (`mysql:8.0.39` 권장) |
| B-5 | GitHub public repo 생성 시점 | Phase 8 종료 후 한 번에. 그 전까지 로컬 main에서 작업 |

차단 결정이 필요한 항목 없음 — 모든 항목이 위 amendments + 사용자 환경 변수로 해결 가능.

---

## 5. Phase 1~6 본 ralph 루프에 전달할 핵심 컨텍스트

Phase 1 진입 시 다음을 입력으로 사용:

```
목표: ARTINUS Backend Engineer 과제 구현
핵심 입력 (우선순위 순):
  1. /Users/imform-mm-2101/workspace/PERSONAL/membership-flow/ASSIGNMENT.md       (요구사항 SoT)
  2. /Users/imform-mm-2101/workspace/PERSONAL/membership-flow/.omc/reviews/2026-05-19-phase0-handoff.md  (본 문서, 정정 SoT)
  3. /Users/imform-mm-2101/workspace/PERSONAL/membership-flow/.omc/plans/2026-05-19-artinus-subscription-plan.md  (역사적 계획, §3 amendments 적용 전제로 참조)
  4. /Users/imform-mm-2101/workspace/PERSONAL/membership-flow/.omc/reviews/2026-05-19-phase0-{critic,architect}.md  (상세 근거)

작업 분해 (Step별 커밋):
  Phase 1: 부트스트랩 (Gradle, deps, app.yml 프로파일, .gitignore) — executor (sonnet)
  Phase 2: 도메인 + Flyway V1 + StateTransitionPolicy (18 케이스 TDD) — deep-executor (opus)
  Phase 3: 병렬 team
    Worker A: external.csrng (RestClient + @Retry + @CircuitBreaker + WireMock 통합 테스트, @TimeLimiter 제거)
    Worker B: external.llm (Gemini RestClient + 별도 Resilience4j 인스턴스 + Mock 프로파일)
  Phase 4: 구독/해지 서비스 (2-Phase TX) + Controller + ProblemDetail GlobalExceptionHandler
  Phase 5: 병렬 team
    Worker C: 이력 조회 + LLM 요약 통합 + Actuator + Swagger
    Worker D: docs/architecture.md (NAT GW, KMS, DR RPO/RTO, WAF 포함)
  Phase 6: 통합 시나리오 테스트 1개 + JaCoCo + README 최종 + verifier + critic 종합

완료 조건:
  - 모든 acceptance criteria 충족
  - ./gradlew test 통과, JaCoCo line coverage 70%+
  - 18개 상태 전이 케이스 통과
  - csrng/LLM 장애 시나리오 (timeout/5xx/random=0/CB open/빈배열) 5+ 케이스 통과
  - .env 미커밋, README에 기술 선택 근거 + 트레이드오프 명시
  - docs/architecture.md에 NAT/KMS/DR/WAF 포함
```

---

## 6. 메타

- **검토 신뢰도:** HIGH — 두 독립 에이전트의 핵심 지적이 일치
- **계획서 완성도 점수:** critic 78/100, architect는 점수 미부여이나 Critical 3건 식별
- **수정 후 예상 완성도:** 92~95/100 (Critical/Major 11건 중 9건 수용)
- **결정자:** 사용자 (본 문서 검토 후 Phase 1 진입 승인 시 ralph 본 루프 가동)
- **본 문서가 SoT:** Phase 1~6 진행 중 계획서와 본 문서 간 충돌 발생 시 **본 문서가 우선**

---

## 7. Architect Verification (Phase 0 Sign-off)

**검증자:** oh-my-claudecode:architect (opus, READ-ONLY) — 2회차 독립 검증
**판정:** **APPROVED_WITH_NOTES** / Confidence: **HIGH**
**MUST_FIX_BEFORE_PHASE1:** 없음

검증자가 식별한 SHOULD_FIX 항목 4건을 Phase별 작업 시 반영:
- **[Phase 2]** `StateTransitionPolicy` 구현 시 "최초 회원 + targetStatus=NONE" 케이스를 의식적으로 테스트 케이스에 포함하고 README에 해석 결정(거부) 근거 명시 (ASSIGNMENT.md:69 "어떤 상태로든 가입" 해석 긴장)
- **[Phase 1]** `application.yml`에 `spring.datasource.hikari.maximum-pool-size: 20` 명시 (VT 환경 기본 10은 보수적)
- **[Phase 3]** `ClientHttpRequestFactorySettings`만으로는 부족, `ClientHttpRequestFactories.get(...)` + `RestClient.builder().requestFactory(...)`까지 전체 패턴 구현 필요
- **[Phase 5]** LLM 20건 제한 근거를 "토큰 비용"이 아닌 "요약 품질 및 프롬프트 집중도"로 README 기술

검증자가 식별한 OPTIONAL 2건:
- phoneNumber 정규식 `^010\d{8}$`가 의도적 결정임을 README에 명시 (확장 패턴 `^01[016789]\d{7,8}$` 참고로 기술)
- Phase A의 `SimpleJpaRepository` 자동 readOnly TX 동작을 코드 주석으로 명시

---

## 8. 다음 액션 (사용자 확인 필요)

본 인계 문서를 승인하면:
1. `.omc/prd.json`의 US-001/US-002/US-003을 `passes: true`로 마킹 (완료됨)
2. Phase 0 작업을 종료하고 Phase 1~6 본 ralph 루프 진입 (사용자가 `/oh-my-claudecode:ralph` 재실행 또는 자동 진입)
3. Phase 1 부트스트랩 워커는 본 문서의 §3 amendments + §5 컨텍스트 + §7 SHOULD_FIX를 입력으로 사용

승인하지 않으면 추가 수정/재검토.
