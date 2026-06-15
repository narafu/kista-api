-- V16: accounts 테이블 컬럼명/순서 정리
--   kis_app_key → app_key, kis_secret_key → secret_key (KIS·Toss 공용 자격증명으로 범용화)
--   account_no_hash를 created_at 앞으로 이동 (컬럼 순서 규칙: pk, fk, 비즈니스 컬럼…, created_at, updated_at)
--
-- 컬럼 순서 변경이 필요하므로 테이블 재생성 패턴 사용 (기존 데이터 이관)

-- ============================================================
-- 1. 의존 FK 제거
-- ============================================================
ALTER TABLE broker_tokens DROP CONSTRAINT kis_tokens_account_id_fkey;
ALTER TABLE strategy DROP CONSTRAINT strategy_account_id_fkey;
ALTER TABLE orders DROP CONSTRAINT orders_account_id_fkey;

-- ============================================================
-- 2. 기존 테이블 리네임 + named 제약/인덱스 정리 (스키마 전역 이름 충돌 방지)
-- ============================================================
ALTER TABLE accounts RENAME TO accounts_old;
-- accounts_pkey 인덱스는 테이블 리네임 시 Postgres가 자동으로 accounts_old_pkey로 변경함
DROP INDEX idx_accounts_user_id;
DROP INDEX uq_accounts_account_no_hash;

-- ============================================================
-- 3. 새 컬럼명/순서로 재생성
-- ============================================================
CREATE TABLE accounts (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL,
    nickname         VARCHAR(100) NOT NULL,
    account_no       VARCHAR(512) NOT NULL,   -- AES-256 암호화 저장
    app_key          VARCHAR(512) NOT NULL,   -- AES-256 암호화 저장
    secret_key       VARCHAR(512) NOT NULL,   -- AES-256 암호화 저장
    kis_account_type VARCHAR(10)  NOT NULL DEFAULT '01',
    broker           VARCHAR(20)  NOT NULL DEFAULT 'KIS',
    account_no_hash  VARCHAR(64),
    deleted_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT accounts_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ============================================================
-- 4. 데이터 이관
-- ============================================================
INSERT INTO accounts (id, user_id, nickname, account_no, app_key, secret_key, kis_account_type,
                       broker, account_no_hash, deleted_at, created_at, updated_at)
SELECT id, user_id, nickname, account_no, kis_app_key, kis_secret_key, kis_account_type,
       broker, account_no_hash, deleted_at, created_at, updated_at
FROM accounts_old;

DROP TABLE accounts_old;

-- ============================================================
-- 5. 인덱스 복원
-- ============================================================
CREATE INDEX idx_accounts_user_id ON accounts(user_id);

CREATE UNIQUE INDEX uq_accounts_account_no_hash
    ON accounts (account_no_hash)
    WHERE deleted_at IS NULL AND account_no_hash IS NOT NULL;

-- ============================================================
-- 6. FK 복원 (broker_tokens FK 제약명도 테이블명에 맞춰 정리)
-- ============================================================
ALTER TABLE broker_tokens ADD CONSTRAINT broker_tokens_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
ALTER TABLE strategy ADD CONSTRAINT strategy_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
ALTER TABLE orders ADD CONSTRAINT orders_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
