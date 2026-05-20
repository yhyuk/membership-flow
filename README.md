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

```
com.artinus.subscription
├── domain          # 회원/채널/구독 엔티티, 도메인 정책 (Phase 2)
├── application     # 유스케이스 서비스 (Phase 4)
├── infrastructure  # JPA Repository, 영속 어댑터 (Phase 2~4)
├── presentation    # REST Controller, ProblemDetail 핸들러 (Phase 4)
├── external
│   ├── csrng       # 외부 트랜잭션 검증 클라이언트 (Phase 3)
│   └── llm         # Gemini 요약 클라이언트 (Phase 5)
└── config          # 전역 설정 (OpenAPI 등)
```

---

## 로컬 실행 절차

### 1. MySQL 기동 (docker-compose)

```bash
docker compose up -d mysql
```

기본 접속 정보(`docker-compose.yml`):

| 키 | 값 |
|---|---|
| host:port | `localhost:3306` |
| database | `subscription` |
| user / password | `subscription` / `subscription` |
| root password | `root` |

### 2. 애플리케이션 기동

```bash
./gradlew bootRun
```

- 기본 프로파일은 `local` (환경변수 `SPRING_PROFILES_ACTIVE`로 변경 가능).
- 환경 변수가 필요한 경우 `.env.example` 참고 후 셸에 export.

### 3. 헬스 체크 & API 문서

- Actuator: <http://localhost:8080/actuator/health>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

### 4. 테스트 실행

```bash
./gradlew test
```

테스트는 `application-test.yml`(H2 인메모리)로 실행되며 docker compose 없이도 통과한다.

---

## API 개요 (Phase 4 이후)

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/subscriptions` | 구독하기 |
| POST | `/api/v1/subscriptions/cancel` | 구독 해지 |
| GET | `/api/v1/members/{phoneNumber}/subscription-histories` | 이력 + LLM 요약 |

---

## 문서

- 과제 원문: [`ASSIGNMENT.md`](ASSIGNMENT.md)
- 작업 계획: [`.omc/plans/2026-05-19-artinus-subscription-plan.md`](.omc/plans/2026-05-19-artinus-subscription-plan.md)
- Phase 0 인계 (정정 SoT): [`.omc/reviews/2026-05-19-phase0-handoff.md`](.omc/reviews/2026-05-19-phase0-handoff.md)
- 클라우드 아키텍처: `docs/architecture.md` (Phase 5에서 작성)

---

## 진행 상태

- [x] 작업 계획 수립
- [x] Phase 0 — 계획 검토 (critic + architect)
- [x] Phase 1 — 프로젝트 부트스트랩
- [x] **Phase 2 — 도메인 & Flyway V1 & StateTransitionPolicy (18 케이스 TDD)** (현재)
- [ ] Phase 3 — csrng 클라이언트 + Resilience4j + LLM 클라이언트
- [ ] Phase 4 — 구독/해지 API (2-Phase TX)
- [ ] Phase 5 — 이력 조회 + LLM 요약 + AWS 아키텍처 문서
- [ ] Phase 6 — 통합 시나리오 테스트 + JaCoCo + 최종 검증
