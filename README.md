# subscription (membership-flow)

> 구독 서비스 백엔드 API

채널 기반 구독/해지/이력 조회 API 서버. 외부 API(csrng) 장애 대응과 LLM 기반 이력 요약을 포함합니다.

---

## 기술 스택

| 구분 | 선택 | 비고 |
|---|---|---|
| Language | Java 21 | LTS |
| Framework | Spring Boot 3.3.x | Web, Validation, Data JPA, Actuator |
| Database | MySQL 8.0.39 | Flyway 마이그레이션 |
| Resilience | Resilience4j (Retry + CircuitBreaker) | 동기 RestClient 호환을 위해 TimeLimiter 미사용 |
| HTTP Client | Spring 6 RestClient | 자체 connect/read timeout 설정 |
| LLM | Google Gemini | 이력 자연어 요약 (Phase 5) |
| API Docs | springdoc-openapi 2.x | Swagger UI |
| Build | Gradle 8.10.2 (Groovy DSL) | |
| Test | JUnit 5 + AssertJ + Mockito | Testcontainers는 Phase 6에서 추가 |

자세한 기술 선택 근거는 [`.omc/plans/2026-05-19-artinus-subscription-plan.md`](.omc/plans/2026-05-19-artinus-subscription-plan.md) 및 [`.omc/reviews/2026-05-19-phase0-handoff.md`](.omc/reviews/2026-05-19-phase0-handoff.md) 참고.

---

## 패키지 구조

feature-sliced 구조. 각 feature 패키지 내부에 `domain`/`application`/`persistence`/`dto` 레이어를 둔다.

```
com.artinus.membership
├── subscription        # 구독/해지 — Controller + Validator/Applier 2-Phase TX + 도메인 정책
│   ├── application     #   SubscriptionService / SubscriptionValidator / SubscriptionApplier
│   ├── domain          #   Subscription, SubscriptionState, StateTransitionPolicy, *Event, *Label
│   ├── dto             #   SubscriptionRequest / SubscriptionResponse
│   └── persistence     #   SubscriptionRepository
├── history             # 이력 조회 + LLM 요약 — HistoryService(NORMAL/DEGRADED/EMPTY)
│   ├── application, domain, dto, persistence
│   └── SubscriptionHistoryController
├── member              # Member 엔티티 + Repository
├── channel             # Channel 엔티티 + Repository (Flyway V1 시드 데이터)
├── csrng               # 외부 트랜잭션 검증 어댑터 (Resilience4j Retry + CircuitBreaker)
├── llm                 # Gemini 요약 어댑터 (PromptTemplate · ThinkingConfig · Resilience4j)
└── common              # ErrorCode, ApiResponse, GlobalExceptionHandler, Clock 등 공통 인프라
```

---

## 로컬 실행 절차

두 가지 모드 중 선택:

| 모드 | 명령 | 용도 |
|---|---|---|
| **A. DB만 컨테이너 + 앱 호스트** | `docker compose up -d mysql` → `./gradlew bootRun` | 개발 중 — 코드 수정/디버깅 빠른 피드백 |
| **B. 풀스택 컨테이너** (앱 포함) | `docker compose --profile app up -d --build` | 운영 환경 모사 — 이미지 기반 통합 검증 |

### 0. 환경 변수 준비

```bash
cp .env.example .env
# 필요 시 GEMINI_API_KEY 등 채워 넣기 — 비워두면 이력 조회 summary는 DEGRADED 응답
```

### 모드 A — DB만 컨테이너

```bash
docker compose up -d mysql
./gradlew bootRun
```

기본 접속 정보:

| 키 | 값 |
|---|---|
| host:port | `localhost:3306` |
| database | `subscription` |
| user / password | `subscription` / `subscription` |
| root password | `root` |

- 기본 프로파일은 `local` (환경변수 `SPRING_PROFILES_ACTIVE`로 변경 가능).
- 환경 변수가 필요한 경우 `.env.example` 참고 후 셸에 export.

### 모드 B — 풀스택 컨테이너 (앱까지 도커)

```bash
docker compose --profile app up -d --build
docker compose --profile app logs -f app    # 부팅 로그 관찰
```

- `Dockerfile`은 멀티스테이지 (JDK 21 builder → JRE 21 runtime, non-root 실행, layered jar)
- 앱 컨테이너는 MySQL healthcheck 통과 후에야 기동 (`depends_on.condition: service_healthy`)
- 컨테이너 내부에서는 DB host가 `mysql` (compose 서비스명)
- JVM 옵션은 `JAVA_OPTS` 환경 변수로 전달 — 기본 `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC`

종료 / 정리:

```bash
docker compose --profile app down              # 컨테이너만 제거 (데이터 유지)
docker compose --profile app down -v           # 볼륨까지 제거 (DB 초기화)
```

### 헬스 체크 & API 문서

- Actuator: <http://localhost:8080/actuator/health>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

### 테스트 실행

```bash
./gradlew test
```

테스트는 `application-test.yml`(H2 인메모리)로 실행되며 docker compose 없이도 통과한다.

---

## API 개요

| Method | Path | 설명 | 상태 |
|---|---|---|---|
| POST | `/api/v1/subscriptions` | 구독/해지 (요청 body의 `targetState`로 분기) | Phase 4 |
| GET | `/api/v1/members/{phoneNumber}/subscription-histories` | 최근 20건 이력 + LLM 요약 (NORMAL/DEGRADED/EMPTY) | Phase 5 |

오류 응답은 RFC 7807 `application/problem+json`을 따른다. 본문에 ErrorCode가
`code` 확장 속성으로 첨부된다. HTTP 상태 매트릭스 9 상황은
[`.omc/reviews/2026-05-19-phase0-handoff.md`](.omc/reviews/2026-05-19-phase0-handoff.md) §3.2 참고.

### 이력 조회 API 응답 시나리오

| 상황 | HTTP | `status` | `summary` | 비고 |
|---|---|---|---|---|
| 이력 1건 이상 + LLM 성공 | 200 | `NORMAL` | 자연어 요약 문자열 | 정상 케이스 |
| 이력 1건 이상 + LLM 실패 | 200 | `DEGRADED` | `null` | api-key 미설정 / 4xx / 5xx / timeout 모두 동일 매핑 |
| 이력 0건 | 200 | `EMPTY` | `null` | LLM 호출 자체를 건너뜀 |
| Member 미존재 | 404 | — | — | ProblemDetail (`RESOURCE_NOT_FOUND`) |
| phoneNumber 형식 위반 | 400 | — | — | ProblemDetail (`VALIDATION_FAILED`) |

LLM 호출 실패가 사용자 응답을 막지 않는다는 결정은 ASSIGNMENT의 "이력 + 요약" 요구가 요약이
보조적임을 시사하기 때문이다. 응답 마스킹: `phoneNumber`는 `010-****-1234` 형태로 부분 마스킹된다.

---

## 문서

- 과제 원문: [`ASSIGNMENT.md`](ASSIGNMENT.md)
- 작업 계획: [`.omc/plans/2026-05-19-artinus-subscription-plan.md`](.omc/plans/2026-05-19-artinus-subscription-plan.md)
- Phase 0 인계 (정정 SoT): [`.omc/reviews/2026-05-19-phase0-handoff.md`](.omc/reviews/2026-05-19-phase0-handoff.md)
- 클라우드 아키텍처: [`docs/architecture.md`](docs/architecture.md) — Mermaid 다이어그램 + NAT/KMS/WAF/RPO·RTO


