# membership-flow

> ARTINUS Backend Engineer 과제 — 구독 서비스 백엔드 API

채널 기반 구독/해지/이력 조회 API 서버. 외부 API(csrng) 장애 대응과 LLM 기반 이력 요약을 포함합니다.

---

## 기술 스택

| 구분 | 선택 | 비고 |
|---|---|---|
| Language | Java 21 | LTS, Virtual Thread |
| Framework | Spring Boot 3.3.x | Web, Validation, Data JPA, Actuator |
| Database | MySQL 8.x | Flyway 마이그레이션 |
| Resilience | Resilience4j | Retry + CircuitBreaker + TimeLimiter + Fallback |
| HTTP Client | Spring 6 RestClient | 동기, Resilience4j 통합 |
| LLM | Google Gemini (`gemini-2.0-flash`) | 이력 자연어 요약 |
| API Docs | springdoc-openapi | Swagger UI |
| Build | Gradle (Kotlin DSL) | |
| Test | JUnit 5, Mockito, Testcontainers(MySQL), WireMock | |

자세한 기술 선택 근거는 [`.omc/plans/2026-05-19-artinus-subscription-plan.md`](.omc/plans/2026-05-19-artinus-subscription-plan.md) 참고.

---

## 도메인 패키지 구조

```
com.artinus.membershipflow
├── common         # 예외, 응답, 설정
├── member         # 회원 도메인
├── channel        # 채널 도메인
├── subscription   # 구독/해지 (상태 머신)
├── history        # 구독 이력 + LLM 요약
└── external
    ├── csrng      # 외부 트랜잭션 검증 API
    └── llm        # Gemini 요약
```

---

## 실행 방법 (작성 예정)

```bash
# 환경 변수 설정 (.env.example 참고)
export GEMINI_API_KEY=...
export DB_URL=jdbc:mysql://localhost:3306/membership_flow
export DB_USERNAME=...
export DB_PASSWORD=...

./gradlew bootRun
```

Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## API 개요

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/subscriptions` | 구독하기 |
| POST | `/api/v1/subscriptions/cancel` | 구독 해지 |
| GET | `/api/v1/members/{phoneNumber}/subscription-histories` | 이력 + LLM 요약 |

---

## 문서

- 과제 원문: [`ASSIGNMENT.md`](ASSIGNMENT.md)
- 작업 계획: [`.omc/plans/2026-05-19-artinus-subscription-plan.md`](.omc/plans/2026-05-19-artinus-subscription-plan.md)
- 클라우드 아키텍처: `docs/architecture.md` (작성 예정)

---

## 진행 상태

- [x] 작업 계획 수립
- [ ] Step 1. 프로젝트 부트스트랩
- [ ] Step 2. 도메인 & 마이그레이션
- [ ] Step 3. csrng 클라이언트 + Resilience4j
- [ ] Step 4. 구독/해지 API
- [ ] Step 5. 이력 조회 + LLM 요약
- [ ] Step 6. 관측성 & 운영
- [ ] Step 7. AWS 아키텍처 문서
- [ ] Step 8. 마무리 점검
