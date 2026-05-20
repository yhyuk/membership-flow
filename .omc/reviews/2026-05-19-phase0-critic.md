# Phase 0 Critic Review -- ARTINUS 구독 서비스 계획서

**검토 모드:** THOROUGH (ADVERSARIAL 에스컬레이션 불필요 -- CRITICAL 발견 0건)

---

## 1. 요구사항 1:1 매핑 표

| 요구사항 | 계획서 위치 | 충족 여부 | 코멘트 |
|---|---|---|---|
| R1: 구독하기 API (휴대폰번호/채널ID/상태) | Section 4.1, Step 4 | **완전** | 요청 필드 3개(phoneNumber, channelId, targetStatus) 일치. 상태 전이 규칙도 Section 2에서 정의. |
| R2: 구독 해지 API | Section 4.2, Step 4 | **완전** | 동일 페이로드 구조. 해지 전이 규칙 정의됨. |
| R3: 구독 이력 조회 API + LLM 요약 | Section 4.3, Step 5, Section 6 | **완전** | 응답 형식(history[] + summary)이 과제 예시와 일치. LLM 실패 시 graceful degradation 정의. |
| R4: 채널 권한 규칙 (6개 채널) | Section 2 엔티티(subscribable/unsubscribable), Step 2 Flyway seed | **완전** | 6개 채널(홈페이지, 모바일앱, 네이버, SKT, 콜센터, 이메일)과 권한 매트릭스 명시. |
| R5: 상태 전이 규칙 | Section 2 "상태 머신" 테이블 | **완전** | 구독: NONE->{BASIC,PREMIUM}, BASIC->{PREMIUM}. 해지: PREMIUM->{BASIC,NONE}, BASIC->{NONE}. 과제와 정확히 일치. |
| R6: csrng 외부 API 호출 + random 처리 | Section 5, Step 3 | **완전** | random=1 커밋, random=0 롤백. CsrngRejectedException 정의. |
| R7: LLM 자연어 요약 생성 | Section 6 | **완전** | Gemini 2.0 Flash 선택. 프롬프트 템플릿 포함. Fallback null 정의. |
| R8: 외부 API 장애 대응 전략 | Section 5 Resilience4j 풀세트 | **완전** | Retry(3회, exponential backoff) + CircuitBreaker(50% threshold) + TimeLimiter(2s) + Fallback. 설정값까지 명시. |
| R9: AWS 클라우드 아키텍처 설계 문서 | Step 7 | **부분** | 항목은 나열(VPC, ECS, RDS, Secrets Manager 등)했으나 구체적 설계 내용은 "작성 예정". 아래 2.1에서 상세 기술. |

**매핑 결과 요약:** 9개 요구사항 중 8개 완전 충족, 1개(R9) 부분 충족. R9는 계획서 특성상 "Step 7에서 작성 예정"이므로 항목 나열 수준은 적절하나, 누락 위험 항목이 존재.

---

## 2. 발견 사항 (카테고리별)

### 2.1 요구사항 누락 (Requirements Gap)

#### [severity:major] R9 AWS 아키텍처 -- NAT Gateway, VPC Endpoint, KMS 키 관리 누락

- **근거:** Step 7 항목에 `VPC: public(ALB) / private-app(ECS) / private-data(RDS) 서브넷`과 `Secrets Manager로 API Key/DB 비밀번호 관리`는 있으나, private 서브넷의 ECS 태스크가 외부 API(csrng, Gemini)를 호출하려면 NAT Gateway 또는 VPC Endpoint가 필수. 이에 대한 언급이 없음.
- **영향:** 아키텍처 문서에 네트워크 경로(private subnet -> internet) 설명이 빠지면 평가자가 네트워크 설계 이해도를 의심할 수 있음. NAT Gateway 비용(월 $32+/AZ + 데이터 전송)도 트레이드오프로 언급해야 함.
- **권장 조치:** Step 7에 다음 항목 추가: (1) NAT Gateway (Multi-AZ 2개) 또는 VPC Endpoint 선택과 비용 트레이드오프, (2) KMS CMK로 RDS/Secrets Manager 암호화, (3) ECR용 VPC Endpoint(비용 절감).

#### [severity:minor] R9 AWS 아키텍처 -- DR RPO/RTO 수치가 계획서에 미정

- **근거:** Step 7에 `RPO/RTO 명시`라고 적혀 있으나 실제 목표 수치가 없음. 이는 Step 7 실행 시 정의하면 되므로 minor.
- **영향:** 없음 (실행 단계에서 정의 예정).
- **권장 조치:** Step 7 실행 시 RPO < 5분(RDS 자동 백업), RTO < 15분(Multi-AZ 자동 failover) 수준을 명시할 것.

### 2.2 설계 오류/맹점 (Design Flaws)

#### [severity:major] 트랜잭션 안에서 외부 HTTP 호출 -- DB 커넥션 점유 시간 및 풀 고갈 위험

- **근거:** Section 5 "트랜잭션 경계"에서 `@Transactional 메서드 내에서 외부 API 호출 → 응답이 실패면 RuntimeException throw → 자동 롤백`이라고 명시. `timeout-duration` 2초 + Retry 3회(200ms * exponential) = 최악 케이스 약 2+0.2+0.4+0.8+2+0.2+0.4+0.8+2 = 최대 ~9초간 DB 커넥션을 점유할 수 있음.
- **영향:** HikariCP 기본 풀 사이즈(10)에서 동시 요청 10개가 모두 csrng 지연을 겪으면 풀 고갈 → 후속 요청 전부 타임아웃. 이는 csrng 장애가 전체 서비스 장애로 전파되는 cascading failure.
- **권장 조치:** 두 가지 중 하나를 선택:
  - **(A) 트랜잭션 분리:** 외부 API 호출을 트랜잭션 밖에서 수행. 결과를 받은 후 트랜잭션 시작 → 상태 검증(낙관락) → 변경 → 이력 적재 → 커밋. random=0이면 트랜잭션 자체를 시작하지 않음.
  - **(B) TimeLimiter를 Retry 바깥에 배치:** 전체 Retry 체인에 대한 총 타임아웃을 설정(예: 5초)하여 최악 케이스를 제한. 단, 이것만으로는 풀 고갈 근본 해결이 안 됨.
  - 권장: **(A)** 채택. 트랜잭션 밖에서 csrng 호출 후, 결과에 따라 트랜잭션을 열거나 예외를 던지는 구조. 이 경우 `random=0`일 때 DB 접근 자체가 불필요.

#### [severity:major] csrng `random=0` 응답에 409 Conflict 사용 -- HTTP 시맨틱 부적합

- **근거:** Section 5에서 `random=0 처리: CsrngRejectedException → 트랜잭션 롤백, 409 CSRNG_REJECTED 반환`. HTTP 409 Conflict는 "요청이 리소스의 현재 상태와 충돌"을 의미(RFC 9110 Section 15.5.10). csrng random=0은 외부 시스템의 무작위 거부이지 리소스 상태 충돌이 아님.
- **영향:** API 소비자가 409를 받으면 리소스 충돌로 오해하여 잘못된 재시도 로직을 구현할 수 있음. 또한 낙관적 락 충돌(OptimisticLockException)도 409로 매핑하고 있어 두 가지 다른 실패 원인이 같은 상태 코드를 공유.
- **권장 조치:** csrng random=0에는 `422 Unprocessable Entity` + 명확한 에러 코드(CSRNG_REJECTED)를 사용하거나, `503 Service Unavailable`을 고려. 409는 낙관적 락 충돌 전용으로 유지. 또는 과제의 의도("예외 발생 -- 트랜잭션 롤백")를 감안하면 `500 Internal Server Error` + 에러 코드도 가능하나, 가장 적절한 것은 422(외부 검증 실패로 처리 불가).

#### [severity:major] LLM 프롬프트에 PII(전화번호) 전달 가능성 -- 마스킹 정책 미정의

- **근거:** Section 6 프롬프트 템플릿에서 `{{history_lines}}`에 어떤 데이터가 포함되는지 명시하지 않음. 이력 데이터에는 회원의 phoneNumber가 FK로 연결되어 있고, 조회 시 phoneNumber를 키로 사용. 프롬프트에 전화번호가 포함되지 않도록 하는 명시적 정책이 없음.
- **영향:** 전화번호가 외부 LLM(Gemini)에 전송되면 개인정보 유출. 과제이므로 실 서비스 수준은 아니지만, 5~8년차 시니어 엔지니어 과제에서 PII 인식을 보여주는 것은 가점 요소.
- **권장 조치:** (1) 프롬프트에 전화번호를 포함하지 않도록 명시. history_lines에는 (날짜, 채널명, 액션, 상태)만 포함. (2) 계획서 Section 6 프롬프트 템플릿의 주석에 "phoneNumber는 LLM에 전달하지 않음" 명시.

#### [severity:major] E.164 정규화 정책 불완전 -- 국가번호 처리 미정의

- **근거:** Section 2에서 `phoneNumber (UNIQUE, VARCHAR(20)) -- E.164 정규화`라고 명시. Section 9에서 `입력시 하이픈/공백 제거 후 E.164 정규화`라고 기술. 그러나 E.164는 국제 전화번호 형식(예: +821012345678)인 반면, 과제 예시의 요청은 `"phoneNumber": "010-1234-5678"` (한국 로컬 형식). 국가번호 +82 추가 여부, 010 → +8210 변환 로직, 이미 +82로 시작하는 번호 처리 등이 미정의.
- **영향:** `01012345678`과 `+821012345678`이 서로 다른 UNIQUE 레코드로 생성되면 동일 회원이 이중 구독 가능. 반대로 일관되게 E.164로 변환하면 과제 응답의 phoneNumber가 `+821012345678`로 나와서 평가자 기대와 다를 수 있음.
- **권장 조치:** 과제 맥락상 한국 번호만 취급한다고 가정하고, 정규화 정책을 명확히 정의:
  - 입력: 하이픈/공백/괄호 제거
  - 저장: `01012345678` (국내 형식, 11자리)
  - UNIQUE 제약: 정규화된 형태 기준
  - E.164 언급은 삭제하거나 "국내 번호 정규화(하이픈 제거, 11자리 검증)"로 변경
  - 또는 E.164를 유지하되 변환 규칙(`010` → `+8210`)을 명시

#### [severity:minor] 동일 상태 재요청(Idempotency) 처리 미정의

- **근거:** Section 2 상태 머신에서 `BASIC → PREMIUM` 등 허용 전이만 정의. 그러나 이미 PREMIUM인 회원이 PREMIUM으로 구독 요청(동일 상태 전이)하는 경우의 처리가 암묵적. Step 4 수용 기준에 `이미 PREMIUM 회원이 다시 PREMIUM 구독 → 422`가 있어 의도는 파악 가능하나, Section 2 상태 머신 테이블에는 "PREMIUM → _(변경 불가)_"로만 표기되어 있어 해지 쪽에서 NONE → NONE 등의 케이스도 동일하게 422인지 명시적이지 않음.
- **영향:** 구현자가 도메인 규칙 객체(`canTransitionTo`)를 작성할 때 이 케이스를 누락할 가능성은 낮지만, 테스트 케이스 정의 시 불명확.
- **권장 조치:** Section 2 상태 머신에 "동일 상태 전이는 불허(422)" 규칙을 한 줄 추가.

#### [severity:minor] csrng 응답 파싱 방어 코드 미언급

- **근거:** Section 5에서 `HTTP 200이어도 status != success이면 실패 처리`는 명시. 그러나 csrng 응답은 JSON 배열(`[{...}]`)이며, 빈 배열 `[]`, 배열에 여러 원소, `status` 필드 자체 누락, 비JSON 응답(HTML 에러 페이지) 등의 엣지 케이스에 대한 방어 언급이 없음.
- **영향:** 파싱 실패 시 예상치 못한 예외로 불명확한 에러 응답이 나갈 수 있음. Resilience4j Retry가 이를 잡아주겠지만, 에러 분류가 부정확해질 수 있음.
- **권장 조치:** Step 3에 csrng 응답 검증 항목 추가: (1) 배열 길이 != 1이면 실패, (2) status 필드 없으면 실패, (3) 비JSON 응답은 RestClientException으로 Retry 대상.

### 2.3 운영/보안 리스크 (Ops/Security Risks)

#### [severity:major] csrng random=0 응답을 502로 반환 -- 과제 요구사항 해석 오류 가능성

- **근거:** Section 4.1에서 `502: csrng random=0(롤백) 또는 fallback 결과`라고 기술. 그러나 과제 원문(ASSIGNMENT.md 54~59행)에서 random=0은 "예외 발생 -- 트랜잭션 롤백"이라고만 명시. 이것은 csrng가 정상 응답(HTTP 200, status: success)을 반환한 경우이므로 "외부 서버 오류(502)"가 아님. 502는 "서버가 게이트웨이/프록시로서 잘못된 응답을 수신"을 의미.
- **영향:** Section 5에서는 이를 `409 CSRNG_REJECTED`로도 정의하고 있어 계획서 내에서 502와 409가 상충. 구현 시 혼란 발생. 또한 평가자가 random=0을 502로 처리하는 것을 HTTP 시맨틱 이해 부족으로 볼 수 있음.
- **권장 조치:** random=0은 외부 API 장애가 아니라 "비즈니스 규칙에 의한 거절"이므로, 502가 아닌 별도 상태 코드 사용. Section 4.1과 Section 5의 상태 코드를 통일:
  - random=0: `422 Unprocessable Entity` (CSRNG_REJECTED) -- 요청은 유효하나 외부 검증 실패
  - csrng 장애/타임아웃/CB Open: `502 Bad Gateway` (EXTERNAL_API_UNAVAILABLE)

#### [severity:minor] 시크릿 관리 -- pre-commit hook 미계획

- **근거:** Section 1에서 `시크릿: 환경변수 + .env.example만 커밋, AWS Secrets Manager 가정`. .gitignore에 `.env`는 포함되어 있으나, 실수로 `application.yml`에 API Key를 하드코딩하는 것을 방지할 pre-commit hook(예: git-secrets, detect-secrets)이 계획에 없음.
- **영향:** 과제 제출 시 실수로 키가 노출될 위험. 과제 제약사항에 "API Key와 같은 인증 정보는 레포지토리에 포함되지 않도록 주의해 주세요"가 명시되어 있음(ASSIGNMENT.md 118행).
- **권장 조치:** Step 1에 `detect-secrets` 또는 `.pre-commit-config.yaml` 설정을 추가하거나, 최소한 제출 전 `git log -p | grep -i "api_key\|password\|secret"` 체크리스트를 Step 8에 추가.

#### [severity:minor] 에러 응답 구조 -- RFC 7807 (Problem Details) 호환 여부 미언급

- **근거:** Section 3에서 `ApiResponse<T>, ErrorResponse`를 정의하지만 구체적인 필드 구조가 없음. Spring Boot 3.x는 RFC 7807 Problem Details를 기본 지원(spring.mvc.problemdetails.enabled=true). 시니어 엔지니어 과제에서 이를 활용하면 가점.
- **영향:** 기능적 영향은 없으나, 표준 에러 형식 미사용은 API 설계 품질에서 감점 가능.
- **권장 조치:** ErrorResponse를 RFC 7807 호환으로 설계하거나, Spring Boot 3의 ProblemDetail 클래스를 활용할 것을 Step 4에 명시.

#### [severity:minor] 테스트 전략 -- JaCoCo 90% 목표의 현실성

- **근거:** Step 8에서 `JaCoCo 70%+ (서비스/도메인은 90%+ 목표)`. 서비스 레이어에는 외부 API 호출, 트랜잭션 관리, 예외 처리 등이 포함되어 있어 90%를 달성하려면 상당한 테스트 코드 작성이 필요.
- **영향:** 일정 압박 시 90% 달성에 매달려 핵심 기능 구현이 지연될 수 있음.
- **권장 조치:** 90%는 목표(target)로 유지하되, 최소 기준(gate)은 70%로 설정. "서비스/도메인 90%"는 시간이 허용하는 경우로 명시.

#### [severity:minor] Conventional Commit 강제 메커니즘 부재

- **근거:** Step 7(Step 순서상 전체)에서 `각 단계는 별도 커밋 단위. 컨벤셔널 커밋 규칙`이라고 명시하나, commitlint, husky 등의 실제 강제 도구가 계획에 없음.
- **영향:** 과제 특성상 1인 개발이므로 실질적 위험은 낮음. 다만 설치 비용도 낮음.
- **권장 조치:** 우선순위 낮음. 시간 여유 시 Step 1에 commitlint + husky 추가 고려. 없어도 블로커 아님.

---

## 3. 종합 점수 및 권고

### 계획서 완성도: **78/100**

**근거:**
- **강점 (간략 언급):**
  - 9개 요구사항 중 8개를 정확하게 다루며 과제 원문과의 정합성이 높음
  - Resilience4j 설정이 구체적 수치까지 포함되어 즉시 구현 가능한 수준
  - 도메인 모델(Member, Channel, SubscriptionHistory)과 상태 머신 설계가 과제 요구와 정확히 일치
  - 패키지 구조가 도메인 중심으로 잘 분리되어 있고 역할이 명확
  - WireMock 기반 외부 API 격리 테스트 전략이 현실적
  - LLM 실패 시 graceful degradation(summary=null, 조회는 200 유지)이 올바른 설계
  - 동시성 전략(낙관적 락)과 충돌 처리(409)가 적절

- **감점 요인:**
  - 트랜잭션 내 외부 HTTP 호출로 인한 커넥션 풀 고갈 위험 미대응 (-8)
  - HTTP 상태 코드 시맨틱 오류(random=0 → 502/409 혼재) (-5)
  - LLM PII 마스킹 정책 미정의 (-4)
  - E.164 정규화 정책 불완전 (-3)
  - AWS 아키텍처 세부 누락(NAT GW, KMS) (-2)

### Phase 1 진입 가능 여부: **Conditional (조건부 승인)**

### 진입 전 반드시 수정이 필요한 항목:

1. **[MUST] 트랜잭션 경계 재설계:** 외부 API 호출을 트랜잭션 밖으로 분리하는 구조로 Section 5 "트랜잭션 경계" 수정. 구체적으로: csrng 호출 → 결과 확인 → 트랜잭션 시작 → 상태 검증/변경/이력 적재 → 커밋. random=0이면 트랜잭션 자체를 열지 않음.

2. **[MUST] HTTP 상태 코드 통일:** Section 4.1의 `502: csrng random=0`과 Section 5의 `409: CSRNG_REJECTED`의 상충을 해소. random=0은 422, csrng 장애는 502로 통일.

3. **[SHOULD] LLM PII 정책 명시:** Section 6 프롬프트에 phoneNumber를 전달하지 않는다는 정책을 한 줄 추가.

4. **[SHOULD] 전화번호 정규화 규칙 구체화:** E.164를 유지할지 국내 형식으로 갈지 결정하고 변환 규칙을 Section 2에 명시.

**MUST 2건을 반영하면 Phase 1 진입 가능. SHOULD 2건은 Phase 2(도메인 설계) 단계에서 반영해도 무방.**

---

## 4. 메타

- **검토자:** oh-my-claudecode:critic
- **검토일:** 2026-05-19
- **검토 모드:** THOROUGH (CRITICAL 발견 0건, MAJOR 5건으로 ADVERSARIAL 에스컬레이션 기준 근접했으나, MAJOR 발견들이 구조적 패턴이 아닌 개별 이슈이므로 THOROUGH 유지)
- **입력 파일:**
  - ASSIGNMENT.md (과제 원문, 132행)
  - .omc/plans/2026-05-19-artinus-subscription-plan.md (계획서, 327행)
  - README.md (기술 스택 요약, 88행)
  - .env.example (환경 변수 정의, 14행)
- **발견 사항 집계:** CRITICAL 0건 / MAJOR 5건 / MINOR 5건
