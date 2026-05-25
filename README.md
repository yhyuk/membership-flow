# subscription (membership-flow)

> 구독 서비스 백엔드 API

채널 기반 구독/해지/이력 조회 API 서버. 외부 API(csrng) 장애 대응과 LLM 기반 이력 요약을 포함합니다.

---

## 문서

| # | 문서 | 내용 |
|---|---|---|
| 01 | [아키텍처 설계 & 프로젝트 구성](docs/01-architecture.md) | 패키지 구조, 2-Phase TX, 상태 머신 도메인 모델, DB 스키마 |
| 02 | [API 명세](docs/02-api.md) | 엔드포인트, 에러 코드 매트릭스, 상태 전이 다이어그램, 마스킹/검증 규칙 |
| 03 | [외부 API 장애 대응](docs/03-resilience.md) | Resilience4j Retry/CircuitBreaker, 2-Phase TX, LLM DEGRADED 흡수 |
| 04 | [클라우드 인프라 설계](docs/04-cloud-infrastructure.md) | AWS 토폴로지, VPC/보안, 가용성·복구(RPO/RTO) |
| 05 | [한계점 & 트레이드오프](docs/05-limitations.md) | 의도적 미적용 항목, 회원 단일 상태 결정, LLM 캐시 미적용 사유 |
| 06 | [AI 협업 엔지니어링](docs/06-ai-engineering.md) | AI 제안 vs 사람 의사결정, 프롬프트 설계, AI 산출물 검증 사례 |

과제 원문: [`ASSIGNMENT.md`](ASSIGNMENT.md)

---

## 기술 스택

| 구분 | 선택 | 비고 |
|---|---|---|
| Language | Java 21 | LTS |
| Framework | Spring Boot 3.3.x | Web, Validation, Data JPA, Actuator |
| Database | MySQL 8.0.39 | Flyway 마이그레이션 |
| Resilience | Resilience4j (Retry + CircuitBreaker) | 동기 RestClient 호환을 위해 TimeLimiter 미사용 |
| HTTP Client | Spring 6 RestClient | 자체 connect/read timeout 설정 |
| LLM | Google Gemini (gemini-2.5-flash) | 이력 자연어 요약 |
| API Docs | springdoc-openapi 2.x | Swagger UI |
| Build | Gradle 8.10.2 (Groovy DSL) | |
| Test | JUnit 5 + AssertJ + Mockito + Testcontainers | 단위 + MySQL 통합 테스트 |

기술 선택 근거는 [`docs/01-architecture.md`](docs/01-architecture.md), [`docs/06-ai-engineering.md`](docs/06-ai-engineering.md) 참고.

---

## 패키지 구조

feature-sliced 구조. 각 feature 패키지 내부에 `domain`/`application`/`dto`/`persistence` 레이어를 둡니다.

```
com.artinus.membership
├── subscription   # 구독/해지 (2-Phase TX + 상태 머신)
├── history        # 이력 조회 + LLM 요약
├── member         # 회원
├── channel        # 채널
├── csrng          # 외부 트랜잭션 검증 어댑터
├── llm            # Gemini 요약 어댑터
└── common         # ApiResponse, 예외 처리 등 공통
```

상세 구조와 설계 의도는 [`docs/01-architecture.md`](docs/01-architecture.md) 참고.

---

## 빠른 실행

DB(MySQL)와 앱을 docker compose 한 번으로 모두 띄웁니다.

```bash
# 1. 환경 변수 준비 (.env)
cp .env.example .env
# GEMINI_API_KEY를 채우면 이력 요약이 동작합니다. 비워두면 status=DEGRADED로 응답(HTTP 200).

# 2. DB + 앱 기동 (앱은 MySQL healthcheck 통과 후 자동 시작)
docker compose up -d --build

# 3. 부팅 로그 확인 (선택)
docker compose logs -f app
```

기동 후:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Health: <http://localhost:8080/actuator/health>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

종료 / 정리:

```bash
docker compose down       # 컨테이너 제거 (DB 데이터 유지)
docker compose down -v    # 볼륨까지 제거 (DB 초기화)
```

> 앱만 로컬에서 디버깅하려면 `docker compose up -d mysql`로 DB만 띄우고 `./gradlew bootRun`을 실행하세요. (DB 기본 접속: `localhost:3306`, db/user/pw 모두 `subscription`)

### 테스트 실행

```bash
./gradlew test
```

단위/슬라이스 테스트는 H2 인메모리로 실행된다. 통합 테스트(`SubscriptionScenarioIntegrationTest`)는
Testcontainers로 실제 MySQL 8을 띄우므로 Docker 데몬이 실행 중이어야 한다.

---

## API 개요

| Method | Path | 설명 | 상태 |
|---|---|---|---|
| POST | `/api/v1/subscriptions` | 구독/해지 (요청 body의 `targetState`로 분기) | Phase 4 |
| GET | `/api/v1/members/{phoneNumber}/subscription-histories` | 최근 20건 이력 + LLM 요약 (NORMAL/DEGRADED/EMPTY) | Phase 5 |

성공/오류 응답 모두 공통 `ApiResponse` 래퍼(`success`/`data`/`message`/`code`/`timestamp`)로 통일된다.
오류 시 `code`에 ErrorCode가 담기며, 검증 실패는 `errors[]`에 필드별 상세가 추가된다.
전체 HTTP 상태 매트릭스와 에러 코드는 [`docs/02-api.md`](docs/02-api.md) 참고.

### 이력 조회 API 응답 시나리오

| 상황 | HTTP | `status` | `summary` | 비고 |
|---|---|---|---|---|
| 이력 1건 이상 + LLM 성공 | 200 | `NORMAL` | 자연어 요약 문자열 | 정상 케이스 |
| 이력 1건 이상 + LLM 실패 | 200 | `DEGRADED` | `null` | api-key 미설정 / 4xx / 5xx / timeout 모두 동일 매핑 |
| 이력 0건 | 200 | `EMPTY` | `null` | LLM 호출 자체를 건너뜀 |
| Member 미존재 | 404 | — | — | `RESOURCE_NOT_FOUND` |
| phoneNumber 형식 위반 | 400 | — | — | `VALIDATION_FAILED` |

LLM 호출 실패가 사용자 응답을 막지 않습니다(요약은 보조 기능). 응답 시 `phoneNumber`는 `010-****-1234` 형태로 부분 마스킹됩니다.



