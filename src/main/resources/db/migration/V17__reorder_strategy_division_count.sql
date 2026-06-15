-- V17: strategy 테이블 컬럼 순서 규칙 준수
--   규칙: pk, fk, 비즈니스 컬럼…, created_at, updated_at, deleted_at
--   위반: division_count(V12, ADD COLUMN으로 deleted_at 뒤에 위치) → created_at 앞으로 이동
--
-- 컬럼 순서 변경이 필요하므로 테이블 재생성 패턴 사용 (기존 데이터 이관)

-- ============================================================
-- 1. strategy를 참조하는 FK 제거
-- ============================================================
ALTER TABLE strategy_cycle DROP CONSTRAINT strategy_cycle_strategy_id_fkey;

-- ============================================================
-- 2. 기존 테이블 리네임 + named 제약/인덱스 정리
-- ============================================================
ALTER TABLE strategy RENAME TO strategy_old;
ALTER INDEX strategy_pkey RENAME TO strategy_old_pkey;
DROP INDEX idx_strategy_account_id;

-- ============================================================
-- 3. 새 컬럼 순서로 재생성 (division_count를 created_at 앞으로)
-- ============================================================
CREATE TABLE strategy (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID         NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    ticker          VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    cycle_seed_type VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    division_count  INTEGER      NOT NULL DEFAULT 20,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT strategy_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

-- ============================================================
-- 4. 데이터 이관
-- ============================================================
INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, division_count,
                       created_at, updated_at, deleted_at)
SELECT id, account_id, type, ticker, status, cycle_seed_type, division_count,
       created_at, updated_at, deleted_at
FROM strategy_old;

DROP TABLE strategy_old;

-- ============================================================
-- 5. 인덱스/FK 복원
-- ============================================================
CREATE INDEX idx_strategy_account_id ON strategy(account_id);

ALTER TABLE strategy_cycle ADD CONSTRAINT strategy_cycle_strategy_id_fkey
    FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE;
