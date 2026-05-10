-- strategies 테이블 생성 (account:strategy = 1:N 관계 지원)
CREATE TABLE strategies (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID            NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type        strategy_type   NOT NULL,
    status      strategy_status NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_strategies_account_id ON strategies(account_id);

-- 기존 accounts 데이터를 strategies 테이블로 마이그레이션
INSERT INTO strategies (account_id, type, status, created_at, updated_at)
SELECT id, strategy, strategy_status, created_at, updated_at
FROM accounts;

-- accounts 테이블에서 strategy 및 strategy_status 컬럼 제거
ALTER TABLE accounts
    DROP COLUMN strategy,
    DROP COLUMN strategy_status;
