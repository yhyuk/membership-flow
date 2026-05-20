-- ============================================================================
-- V1__init.sql — ARTINUS 구독 서비스 초기 스키마
-- ----------------------------------------------------------------------------
-- 설계 결정 (handoff §3 amendments 및 사용자 명시 결정 반영):
--   * ID 전략: BIGINT AUTO_INCREMENT (전 테이블)
--   * phoneNumber: VARCHAR(11), 국내 형식 '01012345678' 정규화 후 저장
--                   UNIQUE — handoff M-4 동시 가입 충돌 처리용
--   * 이력 관리: subscriptions와 분리된 별도 subscription_history (INSERT-only)
--   * Channel: Flyway V1에 시드 데이터 INSERT (운영 채널 6종)
--   * @Version 낙관락: members, subscriptions
--   * subscriptions UNIQUE(member_id, channel_id) 제약은 두지 않는다.
--     이유: ASSIGNMENT "회원은 구독 및 해지를 여러 번 수행할 수 있다" (line 108).
--     같은 채널에서 가입/해지를 반복할 수 있으므로 한 회원이 채널별 단일 행을
--     유지하되 history로 변경 추적. (UNIQUE는 (member_id, channel_id)만, 행 자체는
--     상태 변경/멱등 갱신으로 사용)
--   * 상태 enum: VARCHAR(20) + 애플리케이션 검증 (MySQL 8.0.16+ CHECK 가능하지만
--     스키마 진화 단순성을 위해 애플리케이션 레벨 검증으로 통일)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- members — 회원
-- ---------------------------------------------------------------------------
CREATE TABLE members (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    phone_number VARCHAR(11)  NOT NULL COMMENT '국내 정규화 형식 (^010\\d{8}$)',
    version      BIGINT       NOT NULL DEFAULT 0 COMMENT '낙관적 락',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_members_phone_number (phone_number)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- channels — 구독/해지 창구
-- ---------------------------------------------------------------------------
-- subscribable / unsubscribable 플래그로 채널 타입 표현 (ASSIGNMENT line 26-30)
CREATE TABLE channels (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    code            VARCHAR(50)  NOT NULL COMMENT '채널 식별 코드 (HOMEPAGE/MOBILE/NAVER 등)',
    name            VARCHAR(100) NOT NULL,
    subscribable    BIT(1)       NOT NULL COMMENT '구독 가능 여부',
    unsubscribable  BIT(1)       NOT NULL COMMENT '해지 가능 여부',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channels_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- subscriptions — 회원 × 채널 구독 현재 상태 (이력은 별도 테이블)
-- ---------------------------------------------------------------------------
-- 한 회원이 채널별로 단일 행을 유지. 행 자체가 "현재 상태"이며 변경 시 UPDATE.
-- 모든 변경은 subscription_history에 append-only로 기록.
CREATE TABLE subscriptions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    member_id      BIGINT       NOT NULL,
    channel_id     BIGINT       NOT NULL,
    state          VARCHAR(20)  NOT NULL COMMENT 'NONE | BASIC | PREMIUM',
    subscribed_at  DATETIME(6)  NULL     COMMENT '최초 구독 시각',
    canceled_at    DATETIME(6)  NULL     COMMENT '최종 해지 시각',
    version        BIGINT       NOT NULL DEFAULT 0 COMMENT '낙관적 락',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_subscriptions_member_channel (member_id, channel_id),
    CONSTRAINT fk_subscriptions_member  FOREIGN KEY (member_id)  REFERENCES members (id),
    CONSTRAINT fk_subscriptions_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- subscription_history — 모든 상태 전이의 INSERT-only append log
-- ---------------------------------------------------------------------------
-- member_id / channel_id는 LLM 요약 등 조회 성능을 위해 비정규화하여 적재.
-- previous_state는 첫 전이(생성 직후)일 때 NULL 허용.
CREATE TABLE subscription_history (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    subscription_id BIGINT       NOT NULL,
    member_id       BIGINT       NOT NULL COMMENT '조회 성능 비정규화',
    channel_id      BIGINT       NOT NULL COMMENT '조회 성능 비정규화',
    previous_state  VARCHAR(20)  NULL,
    next_state      VARCHAR(20)  NOT NULL,
    event_type      VARCHAR(20)  NOT NULL COMMENT 'SUBSCRIBE | UNSUBSCRIBE',
    occurred_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_history_member_occurred (member_id, occurred_at DESC),
    CONSTRAINT fk_history_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ============================================================================
-- 시드 데이터 — 채널 (ASSIGNMENT 예시 line 35-42 반영)
-- ============================================================================
INSERT INTO channels (code, name, subscribable, unsubscribable) VALUES
    ('HOMEPAGE',   '홈페이지',   b'1', b'1'),  -- 구독/해지 모두 가능
    ('MOBILE_APP', '모바일앱',   b'1', b'1'),  -- 구독/해지 모두 가능
    ('NAVER',      '네이버',     b'1', b'0'),  -- 구독만 가능
    ('SKT',        'SKT',       b'1', b'0'),  -- 구독만 가능
    ('CALL_CENTER','콜센터',     b'0', b'1'),  -- 해지만 가능
    ('EMAIL',      '이메일',     b'0', b'1');  -- 해지만 가능
