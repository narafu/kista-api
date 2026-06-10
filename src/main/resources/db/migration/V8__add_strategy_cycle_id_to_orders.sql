-- V8: orders 테이블에 strategy_cycle_id 추가 (1계좌 다중 전략 지원 — 주문 격리)
--     컬럼 위치: account_id 다음 (FK 그룹) — 테이블 재생성 패턴 사용

-- ============================================================
-- 1. 기존 테이블 리네임 + named 제약/인덱스 정리 (스키마 전역 이름 충돌 방지)
-- ============================================================
ALTER TABLE orders DROP CONSTRAINT orders_account_id_fkey;
ALTER TABLE orders RENAME TO orders_old;
ALTER INDEX IF EXISTS orders_pkey RENAME TO orders_old_pkey;
ALTER INDEX IF EXISTS orders_new_pkey RENAME TO orders_old_pkey;

-- ============================================================
-- 2. 새 컬럼 순서로 재생성 (strategy_cycle_id를 account_id 다음에 배치)
-- ============================================================
CREATE TABLE orders (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id        UUID          NOT NULL,
    strategy_cycle_id UUID          NOT NULL,
    trade_date        DATE          NOT NULL,     -- UTC(=US 거래일) 저장, 코드는 KST — TradeDateConverter 경유
    ticker            VARCHAR(20)   NOT NULL,
    order_type        VARCHAR(10)   NOT NULL,
    direction         VARCHAR(5)    NOT NULL,
    price             NUMERIC(12,2) NOT NULL,
    quantity          INT           NOT NULL,
    status            VARCHAR(20)   NOT NULL,      -- PLANNED/PLACED/FILLED/PARTIALLY_FILLED/FAILED/CANCELLED
    kis_order_id      VARCHAR(30),
    filled_quantity   INT,                          -- 체결 수량 (null=미체결)
    filled_price      NUMERIC(12,2),                -- 체결 가중평균가 (null=미체결)
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT orders_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT orders_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE
);

-- ============================================================
-- 3. 데이터 이관 — strategy_cycle_id는 같은 계좌의 strategy를 거쳐
--    주문 생성 시점(created_at) 이전에 시작된 가장 최근 strategy_cycle로 백필
--    (1:1 시절 데이터 — 계좌당 strategy 1개 가정)
-- ============================================================
INSERT INTO orders (id, account_id, strategy_cycle_id, trade_date, ticker, order_type, direction,
                     price, quantity, status, kis_order_id, filled_quantity, filled_price, created_at, updated_at)
SELECT o.id, o.account_id, sc.id, o.trade_date, o.ticker, o.order_type, o.direction,
       o.price, o.quantity, o.status, o.kis_order_id, o.filled_quantity, o.filled_price, o.created_at, o.updated_at
FROM orders_old o
JOIN strategy s ON s.account_id = o.account_id AND s.deleted_at IS NULL
JOIN LATERAL (
    SELECT sc.id FROM strategy_cycle sc
    WHERE sc.strategy_id = s.id AND sc.deleted_at IS NULL
    ORDER BY (sc.created_at <= o.created_at) DESC, sc.created_at DESC
    LIMIT 1
) sc ON true;

-- ============================================================
-- 4. 구 테이블 제거
-- ============================================================
DROP TABLE orders_old;
