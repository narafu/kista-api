-- strategies 테이블을 재생성하여 ticker 컬럼을 type 바로 다음에 배치
-- (PostgreSQL ADD COLUMN은 위치 지정 미지원). ticker 값은 accounts.symbol에서 이관.

ALTER TABLE strategies RENAME TO strategies_old;

CREATE TABLE strategies (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID            NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type        strategy_type   NOT NULL,
    ticker      VARCHAR(20)     NOT NULL,
    status      strategy_status NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- 기존 strategies 데이터 + accounts.symbol을 조인해 이관
INSERT INTO strategies (id, account_id, type, ticker, status, created_at, updated_at)
SELECT s.id, s.account_id, s.type, a.symbol, s.status, s.created_at, s.updated_at
FROM strategies_old s
JOIN accounts a ON a.id = s.account_id;

DROP TABLE strategies_old;

CREATE INDEX idx_strategies_account_id ON strategies(account_id);

-- accounts에서 symbol 컬럼 제거 (strategies.ticker로 이관 완료)
ALTER TABLE accounts DROP COLUMN symbol;
