-- V20: 리버스모드 SSOT를 strategy_cycle → cycle_position 이전
--   1) strategy_cycle.is_reverse_mode 컬럼 삭제 (in-place)
--   2) cycle_position 재생성 — is_reverse_mode BOOLEAN NOT NULL DEFAULT FALSE
--      컬럼 순서: id, strategy_cycle_id, usd_deposit, closing_price, avg_price, holdings,
--                 is_reverse_mode, created_at, deleted_at
--      (is_reverse_mode를 created_at 앞에 배치 → 테이블 재생성 필요)

-- ============================================================
-- 1. cycle_position 재생성 (is_reverse_mode를 holdings 뒤·created_at 앞에 삽입)
--    * strategy_cycle.is_reverse_mode가 아직 존재하는 시점에서 실행 → backfill 가능
-- ============================================================

ALTER TABLE cycle_position DROP CONSTRAINT cycle_position_strategy_cycle_id_fkey;

ALTER TABLE cycle_position RENAME TO cycle_position_old;
ALTER INDEX cycle_position_pkey RENAME TO cycle_position_old_pkey;
DROP INDEX idx_cycle_position_strategy_cycle_id;

CREATE TABLE cycle_position (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID          NOT NULL,
    usd_deposit       NUMERIC(20,2) NOT NULL,
    closing_price     NUMERIC(12,2),
    avg_price         NUMERIC(20,2),
    holdings          INT           NOT NULL,
    is_reverse_mode   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT cycle_position_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE
);

-- 기존 포지션 이관 — is_reverse_mode는 각 사이클의 strategy_cycle.is_reverse_mode로 backfill
-- (사이클 내 모든 행에 동일하게 기록: 이전 모드 상태가 position별로 구분되지 않으므로 단순 전파)
INSERT INTO cycle_position (id, strategy_cycle_id, usd_deposit, closing_price, avg_price, holdings,
                             is_reverse_mode, created_at, deleted_at)
SELECT cp.id, cp.strategy_cycle_id, cp.usd_deposit, cp.closing_price, cp.avg_price, cp.holdings,
       COALESCE(sc.is_reverse_mode, FALSE),
       cp.created_at, cp.deleted_at
FROM cycle_position_old cp
LEFT JOIN strategy_cycle sc ON sc.id = cp.strategy_cycle_id;

DROP TABLE cycle_position_old;

CREATE INDEX idx_cycle_position_strategy_cycle_id ON cycle_position(strategy_cycle_id);

-- ============================================================
-- 2. strategy_cycle.is_reverse_mode 컬럼 삭제
--    (cycle_position 재생성 후 삭제해야 backfill JOIN이 가능)
-- ============================================================

ALTER TABLE strategy_cycle DROP COLUMN is_reverse_mode;
