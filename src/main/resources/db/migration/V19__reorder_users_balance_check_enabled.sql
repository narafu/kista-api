-- V19: users 테이블 컬럼 순서 규칙 준수
--   규칙: pk, fk, 비즈니스 컬럼…, created_at, updated_at, deleted_at (deleted_at 보유 시 created_at 앞)
--   위반: balance_check_enabled(V14, ADD COLUMN으로 updated_at 뒤에 위치) → created_at 앞으로 이동
--
-- 컬럼 순서 변경이 필요하므로 테이블 재생성 패턴 사용 (기존 데이터 이관)

-- ============================================================
-- 1. users를 참조하는 FK 제거
-- ============================================================
ALTER TABLE accounts DROP CONSTRAINT accounts_user_id_fkey;
ALTER TABLE fcm_device_tokens DROP CONSTRAINT fcm_device_tokens_user_id_fkey;
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_admin_id_fkey;

-- ============================================================
-- 2. 기존 테이블 리네임 + named 제약 정리
-- ============================================================
ALTER TABLE users RENAME TO users_old;
ALTER INDEX users_pkey RENAME TO users_old_pkey;
ALTER TABLE users_old RENAME CONSTRAINT users_kakao_id_key TO users_old_kakao_id_key;

-- ============================================================
-- 3. 새 컬럼 순서로 재생성 (balance_check_enabled를 created_at 앞으로)
-- ============================================================
CREATE TABLE users (
    id                    UUID         PRIMARY KEY,
    kakao_id              VARCHAR(50)  NOT NULL UNIQUE,
    nickname              VARCHAR(100),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    role                  VARCHAR(20)  NOT NULL DEFAULT 'USER',
    telegram_bot_token    VARCHAR(512),              -- AES-256 암호화 저장
    telegram_chat_id      VARCHAR(50),
    telegram_bot_username VARCHAR(64),
    last_reapplied_at     TIMESTAMPTZ,
    notification_channel  VARCHAR(20)  NOT NULL DEFAULT 'TELEGRAM',
    balance_check_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- 4. 데이터 이관
-- ============================================================
INSERT INTO users (id, kakao_id, nickname, status, role, telegram_bot_token, telegram_chat_id,
                    telegram_bot_username, last_reapplied_at, notification_channel,
                    balance_check_enabled, deleted_at, created_at, updated_at)
SELECT id, kakao_id, nickname, status, role, telegram_bot_token, telegram_chat_id,
       telegram_bot_username, last_reapplied_at, notification_channel,
       balance_check_enabled, deleted_at, created_at, updated_at
FROM users_old;

DROP TABLE users_old;

-- ============================================================
-- 5. FK 복원
-- ============================================================
ALTER TABLE accounts ADD CONSTRAINT accounts_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE fcm_device_tokens ADD CONSTRAINT fcm_device_tokens_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_admin_id_fkey
    FOREIGN KEY (admin_id) REFERENCES users(id) ON DELETE CASCADE;
