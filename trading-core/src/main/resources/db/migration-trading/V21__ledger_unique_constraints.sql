-- 원장을 지키는 유일 제약 2개 — 재개 결함·수동 실행과 배치의 겹침으로 같은 일이 두 번 돌아도 이중 주문·이중 사이클 대신 저장 실패가 나게 한다
-- 적용 전 확인: 아래 두 조건의 GROUP BY … HAVING count(*) > 1 이 운영에서 0건이어야 한다(2026-11-15 0건)

-- 같은 슬롯(사이클·거래일·접수 시점·방향·leg)에 살아 있는 주문은 하나
-- 취소·실패 행은 제약 밖이라 재접수와 미체결 취소 누적을 막지 않는다. leg 도입 전 주문과 수동 체결 보정(UNKNOWN)도 제약 밖이다
CREATE UNIQUE INDEX uq_orders_live_slot
    ON trading.orders (strategy_cycle_id, trade_date, timing, direction, order_leg)
    WHERE status IN ('PLANNED', 'SUBMITTING', 'PLACED', 'PARTIALLY_FILLED', 'FILLED')
      AND order_leg <> 'UNKNOWN';

-- 전략당 열린 사이클은 하나
CREATE UNIQUE INDEX uq_strategy_cycle_open
    ON trading.strategy_cycle (strategy_id)
    WHERE end_date IS NULL AND deleted_at IS NULL;
