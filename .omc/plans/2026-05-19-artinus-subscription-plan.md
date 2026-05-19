# ARTINUS 구독 서비스 백엔드 작업 계획

**작성일:** 2026-05-19
**대상:** ARTINUS Backend Engineer (5~8년) 과제
**저장소:** PERSONAL/2603_be_recurit

---

## 1. 기술 스택 결정

| 항목 | 선택 | 근거 |
|---|---|---|
| 언어/런타임 | Java 21 | LTS, Virtual Thread로 외부 API 동기 호출의 동시성 부담 완화 |
| 프레임워크 | Spring Boot 3.3.x | 최신 안정 LTS, Spring 6, Jakarta EE 9+ |
| DB | MySQL 8.x | 익숙한 스택, JPA + Flyway로 스키마 관리 |
| ORM | Spring Data JPA + QueryDSL | 이력 조회 동적 쿼리 대비 |
| 빌드 | Gradle (Kotlin DSL) | 표준 |
| 장애 대응 | Resilience4j (Retry + CircuitBreaker + TimeLimiter + Fallback) | Spring Boot 3 공식 통합 |
| HTTP 클라이언트 | Spring 6 RestClient | 동기, 가독성, Resilience4j와 궁합 |
| LLM | Google Gemini API (`gemini-2.0-flash`) | 빠르고 저렴, 한국어 요약 품질 양호. RestClient 직접 호출 |
| 패키지 구조 | 도메인 중심(domain-centric) | `subscription`, `member`, `channel`, `history`, `external.csrng`, `external.llm` |
| 테스트 | JUnit5 + Mockito + Testcontainers (MySQL) + WireMock | 외부 API/LLM은 WireMock으로 격리 |
| API 문서 | springdoc-openapi | Swagger UI |
| 시크릿 | 환경변수 + `.env.example`만 커밋, AWS Secrets Manager 가정 | API Key 누출 방지 |

---

## 2. 도메인 모델 설계

### 엔티티

```
Member (회원)
 - id (PK, BIGINT)
 - phoneNumber (UNIQUE, VARCHAR(20))  -- E.164 정규화
 - currentSubscriptionStatus (ENUM: NONE, BASIC, PREMIUM)
 - createdAt, updatedAt

Channel (채널)
 - id (PK)
 - code (UNIQUE: HOMEPAGE, MOBILE_APP, NAVER, SKT, CALLCENTER, EMAIL)
 - name
 - subscribable (BOOLEAN)
 - unsubscribable (BOOLEAN)

SubscriptionHistory (이력)
 - id (PK)
 - memberId (FK)
 - channelId (FK)
 - actionType (ENUM: SUBSCRIBE, UNSUBSCRIBE)
 - previousStatus (ENUM)
 - newStatus (ENUM)
 - actedAt (TIMESTAMP)
 - externalApiRandomValue (INT)        -- csrng random 기록(감사용)
 - INDEX (memberId, actedAt)
```

### 상태 머신 (Subscription State Machine)

| 액션 | from → to 허용 집합 |
|---|---|
| SUBSCRIBE | NONE → {BASIC, PREMIUM}, BASIC → {PREMIUM} |
| UNSUBSCRIBE | PREMIUM → {BASIC, NONE}, BASIC → {NONE} |

- 전이 규칙을 `SubscriptionStatus` enum 내부 메서드 `canTransitionTo(action, target)`로 캡슐화.
- 채널의 `subscribable / unsubscribable` 플래그도 도메인에서 검증.

### 동시성 안전성
- 동일 회원 동시 요청 대비 → **회원 단위 비관적 락(`SELECT ... FOR UPDATE`)** 또는 낙관적 락(`@Version`) 중 **낙관적 락**을 채택 (`Member.version`).
- 충돌 발생 시 `OptimisticLockException` → 409 Conflict로 매핑.

---

## 3. 패키지 구조 (도메인 중심)

```
com.artinus.subscription
├── ArtinusApplication.java
├── common
│   ├── exception        # GlobalExceptionHandler, ErrorCode
│   ├── response         # ApiResponse<T>, ErrorResponse
│   └── config           # WebConfig, OpenApiConfig, Resilience4jConfig
├── member
│   ├── domain           # Member, MemberRepository
│   ├── application      # MemberService
│   └── api              # (필요시) MemberController
├── channel
│   ├── domain           # Channel, ChannelRepository, ChannelCode
│   └── application      # ChannelService
├── subscription
│   ├── domain           # SubscriptionStatus(enum), SubscriptionAction(enum), StateTransitionPolicy
│   ├── application      # SubscriptionService (subscribe/unsubscribe)
│   ├── api              # SubscriptionController
│   └── dto              # SubscribeRequest, UnsubscribeRequest, SubscriptionResponse
├── history
│   ├── domain           # SubscriptionHistory, SubscriptionHistoryRepository
│   ├── application      # SubscriptionHistoryService
│   ├── api              # SubscriptionHistoryController
│   └── dto              # HistoryResponse(history[], summary)
└── external
    ├── csrng
    │   ├── CsrngClient            # RestClient + @Retry + @CircuitBreaker + @TimeLimiter
    │   ├── CsrngResponse
    │   └── CsrngException
    └── llm
        ├── LlmSummaryClient       # Gemini 호출
        ├── GeminiRequest/Response
        └── prompt/PromptTemplate.java
```

---

## 4. API 설계

### Base URL: `/api/v1`

| Method | Path | 설명 |
|---|---|---|
| POST | `/subscriptions` | 구독하기 |
| POST | `/subscriptions/cancel` | 구독 해지 |
| GET | `/members/{phoneNumber}/subscription-histories` | 이력 조회 (LLM 요약 포함) |

### 4.1 POST /api/v1/subscriptions
```json
Request:
{
  "phoneNumber": "010-1234-5678",
  "channelId": 1,
  "targetStatus": "PREMIUM"
}
Response 200:
{
  "memberId": 12,
  "phoneNumber": "01012345678",
  "previousStatus": "BASIC",
  "currentStatus": "PREMIUM",
  "channelCode": "HOMEPAGE",
  "actedAt": "2026-05-19T15:00:00+09:00"
}
```
- 422: 잘못된 상태 전이, 채널 권한 없음
- 502: csrng `random=0`(롤백) 또는 fallback 결과
- 409: 낙관락 충돌

### 4.2 POST /api/v1/subscriptions/cancel
- 동일한 페이로드 / 응답. `targetStatus`는 BASIC 또는 NONE.

### 4.3 GET /api/v1/members/{phoneNumber}/subscription-histories
```json
{
  "history": [
    {"channel":"HOMEPAGE","actionType":"SUBSCRIBE","status":"BASIC","actedAt":"2026-01-01T..."},
    {"channel":"MOBILE_APP","actionType":"SUBSCRIBE","status":"PREMIUM","actedAt":"2026-02-01T..."},
    {"channel":"CALLCENTER","actionType":"UNSUBSCRIBE","status":"NONE","actedAt":"2026-03-01T..."}
  ],
  "summary": "2026년 1월 1일 홈페이지를 통해 일반 구독으로 가입한 뒤..."
}
```
- 이력 없음 → `history: []`, `summary: ""` (LLM 호출 생략)
- LLM 실패 시 `summary: null` + 헤더 `X-Summary-Status: degraded` (서비스 정상 유지)

---

## 5. 외부 API 장애 대응 전략 (csrng)

### Resilience4j 풀세트
```yaml
resilience4j:
  retry:
    instances:
      csrng:
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2
        retry-exceptions: [java.io.IOException, org.springframework.web.client.RestClientException]
  circuitbreaker:
    instances:
      csrng:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
  timelimiter:
    instances:
      csrng:
        timeout-duration: 2s
```
- `@Retry → @CircuitBreaker → @TimeLimiter` 순서로 어노테이션 (외부 → 내부).
- **Fallback 정책:** csrng 호출 실패/타임아웃/CB Open 시 **트랜잭션 롤백**, 사용자에게는 `502 EXTERNAL_API_UNAVAILABLE` 반환.
- **응답 검증:** HTTP 200이어도 `status != success`이면 실패 처리.
- **`random=0` 처리:** 정상 응답이지만 비즈니스 실패. `CsrngRejectedException` → 트랜잭션 롤백, `409 CSRNG_REJECTED` 반환.
- **Actuator 노출:** `/actuator/health`, `/actuator/circuitbreakers`, `/actuator/metrics`로 회로 상태 가시화.

### 트랜잭션 경계
- `@Transactional` 메서드 내에서 외부 API 호출 → 응답이 실패면 `RuntimeException` throw → 자동 롤백.
- 단, 외부 API 호출이 DB 락을 오래 잡지 않도록 **상태 검증/락 → 외부 호출 → 상태 변경 → 이력 적재** 순서를 한 트랜잭션으로 묶고, `timeout-duration` 2초 보장.

---

## 6. LLM 연동 (Gemini)

- 엔드포인트: `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={API_KEY}`
- API Key: `GEMINI_API_KEY` 환경변수 (`application.yml`에서 `${GEMINI_API_KEY:}` 참조)
- 프롬프트 템플릿 (Java text block):
  ```
  다음은 한 회원의 구독 이력입니다. 시간 순서대로 자연스러운 한국어 문장으로 요약하세요.
  - 각 이력은 (날짜, 채널, 액션, 변경된 상태)로 구성됩니다.
  - 마지막에는 현재 상태를 명시해 주세요.
  - 2~4 문장으로 간결하게.

  이력:
  {{history_lines}}
  ```
- 별도 Resilience4j 인스턴스 `llm` 으로 분리 (Retry 2회, Timeout 3s, Fallback = null).
- LLM 실패는 **이력 조회 기능을 막지 않는다** (조회는 핵심, 요약은 부가).

---

## 7. 구현 단계 (Work Breakdown)

> 각 단계는 별도 커밋 단위. 컨벤셔널 커밋 규칙.

### Step 1. 프로젝트 부트스트랩
- [ ] Gradle, Spring Boot 3.3 + Java 21 셋업
- [ ] 의존성: web, validation, data-jpa, mysql-connector, flyway, resilience4j-spring-boot3, springdoc, lombok, testcontainers, wiremock
- [ ] `application.yml` 프로파일 분리 (local, test, prod)
- [ ] `.env.example`, `.gitignore` (API Key 보호)
- **수용 기준:** `./gradlew build` 성공

### Step 2. 도메인 & 마이그레이션
- [ ] `Member`, `Channel`, `SubscriptionHistory` 엔티티
- [ ] `SubscriptionStatus`, `SubscriptionAction`, `ChannelCode` enum
- [ ] `StateTransitionPolicy` 도메인 규칙 객체 + 단위 테스트
- [ ] Flyway V1: 테이블 생성, 채널 초기 데이터(6개 row) seed
- **수용 기준:** Testcontainers 기동 후 마이그레이션 통과, enum 전이 규칙 테스트 12개 케이스 통과

### Step 3. csrng 클라이언트 + Resilience4j
- [ ] `CsrngClient` 인터페이스, `RestClient` 구현체
- [ ] `@Retry`, `@CircuitBreaker`, `@TimeLimiter` 적용
- [ ] `random=0` → `CsrngRejectedException`, fallback → `CsrngUnavailableException`
- [ ] WireMock 기반 통합 테스트: success, random=0, 5xx, timeout, CB open
- **수용 기준:** 회로차단기가 5번 연속 실패에 Open되어 후속 호출 즉시 fallback

### Step 4. 구독하기 / 해지 API
- [ ] `SubscriptionService.subscribe(...)` / `unsubscribe(...)`
- [ ] 채널 권한 검증 + 상태 전이 검증 (도메인 위임)
- [ ] 낙관락(`@Version`), `OptimisticLockException` → 409
- [ ] `SubscriptionHistory` 적재 (성공 시에만)
- [ ] `SubscriptionController` + `@Valid` 입력 검증 + 전화번호 정규화
- [ ] `GlobalExceptionHandler`로 ErrorCode → HTTP status 매핑
- **수용 기준:**
  - 신규 회원 → PREMIUM 구독 성공 (`Member` 생성 + 이력 1건)
  - 이미 PREMIUM 회원이 다시 PREMIUM 구독 → 422
  - 구독만 가능 채널로 해지 요청 → 422
  - csrng `random=0` → 롤백, 회원 상태 그대로
  - csrng 타임아웃 → 롤백 + 502

### Step 5. 이력 조회 + LLM 요약
- [ ] `SubscriptionHistoryService.findByPhone(...)` (페이징 없이 시간 ASC)
- [ ] `LlmSummaryClient` (Gemini)
- [ ] LLM 실패 시 `summary=null` + 로그 경고 (조회 자체는 200 유지)
- [ ] 이력 0건 시 LLM 호출 스킵
- **수용 기준:** WireMock으로 Gemini 응답 stub해 요약 포함 200, Gemini 5xx여도 `history`는 200 응답

### Step 6. 관측성 & 운영
- [ ] Actuator (health, info, metrics, circuitbreakers) 노출
- [ ] 구조화 로그 (logback JSON encoder)
- [ ] OpenAPI 문서화 + Swagger UI
- [ ] README: 실행 방법, API 명세, 기술 선택 근거, 트레이드오프

### Step 7. 클라우드 아키텍처 문서 (`docs/architecture.md`)
- [ ] Mermaid 구성도: Route53 → ALB → ECS Fargate → RDS MySQL (Multi-AZ) + ElastiCache(Redis, 선택)
- [ ] Secrets Manager로 API Key/DB 비밀번호 관리
- [ ] VPC: public(ALB) / private-app(ECS) / private-data(RDS) 서브넷
- [ ] 보안: SG 최소권한, IAM Role(태스크 단위), TLS 종단(ACM), WAF
- [ ] 확장성: ECS Auto Scaling(CPU+RequestCount), RDS Read Replica, ElastiCache로 채널 조회 캐시
- [ ] 관측: CloudWatch Logs/Metrics, Container Insights, X-Ray, Resilience4j 메트릭 → CloudWatch
- [ ] 배포: GitHub Actions → ECR push → ECS Rolling/Blue-Green, Flyway는 Migration Task로 별도
- [ ] DR: RDS 자동 백업, Multi-AZ, RPO/RTO 명시

### Step 8. 마무리 점검
- [ ] 통합 시나리오 테스트 (시작 → 변경 → 해지 → 이력 조회) 1개
- [ ] `./gradlew test` 전 통과, JaCoCo 70%+ (서비스/도메인은 90%+ 목표)
- [ ] README 최종 정리, GitHub public repo 푸시, recruit@artinus.dev 회신 준비

---

## 8. 수용 기준 (Acceptance Criteria) — 전체

- [ ] 6개 채널 권한 매트릭스가 DB seed로 일치
- [ ] 상태 전이 규칙(구독/해지) 모든 케이스 단위 테스트 통과
- [ ] csrng `random=0` → 트랜잭션 롤백 검증
- [ ] csrng 장애(timeout/5xx) 시 회로 Open, fallback 동작
- [ ] LLM 실패해도 이력 조회 API는 200
- [ ] API Key/DB 비번이 코드/저장소에 노출되지 않음 (`.env.example`만)
- [ ] OpenAPI/Swagger 문서 접근 가능
- [ ] AWS 아키텍처 문서가 보안/확장성/관측성/배포/DR을 모두 다룸
- [ ] README에 기술 선택 근거, 트레이드오프 명시

---

## 9. 리스크 & 대응

| 리스크 | 대응 |
|---|---|
| csrng 네트워크 불안정 → 테스트 재현성 | 테스트는 WireMock으로 격리. 실제 호출은 Smoke test에서만 |
| Gemini API 쿼터 초과 | Retry 2회/Timeout 3s/Fallback null + 로컬 캐시(선택) |
| 동일 회원 동시 요청 → race condition | `@Version` 낙관락, 충돌 시 409 + 재시도 가이드 |
| MySQL ENUM 컬럼 마이그레이션 부담 | DB는 VARCHAR + CHECK, JPA는 `@Enumerated(STRING)` |
| 전화번호 포맷 다양 | 입력시 하이픈/공백 제거 후 E.164 정규화, UNIQUE 인덱스 |
| 평가자의 LLM 키 부재 | `application-local.yml` 예시 + Mock 프로파일 제공 |

---

## 10. 우선순위 / 일정 가이드

1순위(필수): Step 1 → 2 → 3 → 4 → 5
2순위(평가 가점): Step 6, 7
3순위(여유 시): 부하 테스트(JMeter), 캐시(Redis), 이벤트 발행(Outbox) — 문서로만 언급

---

## 11. 다음 액션

이 계획을 승인하시면 **Step 1 (프로젝트 부트스트랩)** 부터 순차 진행합니다. 필요하다면:
- 특정 Step의 구현 세부(예: csrng 클라이언트 코드 골격, 상태 머신 정책) 먼저 보여드릴 수 있음
- 일부 Step을 통합하거나 우선순위 조정 가능
