-- V49: trading_cycle_history에 current_price 컬럼 추가 (avg_price 앞)
-- 컬럼 순서 목표: id, trading_cycle_id, usd_deposit, current_price, avg_price, holdings, created_at
-- 방식: FK 드롭 → 리네임 → 새 테이블 생성 → INSERT SELECT → DROP → FK + 인덱스 재생성

ALTER TABLE trading_cycle_history DROP CONSTRAINT trading_cycle_history_trading_cycle_id_fkey;

ALTER TABLE trading_cycle_history RENAME TO trading_cycle_history_old;

CREATE TABLE trading_cycle_history (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    trading_cycle_id UUID          NOT NULL,
    usd_deposit      NUMERIC(20,2) NOT NULL,
    current_price    NUMERIC(12,2),
    avg_price        NUMERIC(20,2),
    holdings         INT           NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO trading_cycle_history
       (id, trading_cycle_id, usd_deposit, current_price, avg_price, holdings, created_at)
SELECT  id, trading_cycle_id, usd_deposit, NULL,          avg_price, holdings, created_at
FROM trading_cycle_history_old;

DROP TABLE trading_cycle_history_old;

ALTER TABLE trading_cycle_history ADD CONSTRAINT trading_cycle_history_trading_cycle_id_fkey
    FOREIGN KEY (trading_cycle_id) REFERENCES trading_cycle(id) ON DELETE CASCADE;

CREATE INDEX idx_trading_cycle_history_cycle_id ON trading_cycle_history(trading_cycle_id);
