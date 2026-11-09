-- CQ-67: 사이클 개장 시가 평가금 — 통계 원금·실현 기준. 매매 계약 start_amount(PRIVACY 중간 시작은 FIDA 배수 환산값)와 분리한다
ALTER TABLE trading.strategy_cycle ADD COLUMN open_amount NUMERIC(20,2);

-- 백필: 첫 스냅샷에 종가가 있고 보유가 있으면 그 종가로 평가한 개장 금액(첫 행에 걸린 추가·인출은 되돌린 값).
-- 종가가 없거나(DCA — 원금은 V14 정책상 원가 기준) 보유가 없으면 start_amount − 추가·인출 합계(지금까지의 통계 원금, 변화 없음).
WITH first_pos AS (
    SELECT DISTINCT ON (cp.strategy_cycle_id) cp.id, cp.strategy_cycle_id, cp.usd_deposit, cp.holdings, cp.closing_price
    FROM trading.cycle_position cp
    WHERE cp.deleted_at IS NULL
    ORDER BY cp.strategy_cycle_id, cp.created_at, cp.id
),
row_events AS (
    SELECT cycle_position_id, SUM(deposit_delta) AS deposit_delta, SUM(holdings_delta) AS holdings_delta
    FROM trading.cycle_capital_event
    GROUP BY cycle_position_id
),
cycle_events AS (
    SELECT strategy_cycle_id, SUM(principal_delta) AS principal_delta
    FROM trading.cycle_capital_event
    GROUP BY strategy_cycle_id
)
UPDATE trading.strategy_cycle c
SET open_amount = CASE
        WHEN f.closing_price IS NOT NULL AND f.holdings - COALESCE(re.holdings_delta, 0) > 0
            THEN ROUND((f.usd_deposit - COALESCE(re.deposit_delta, 0))
                       + (f.holdings - COALESCE(re.holdings_delta, 0)) * f.closing_price, 2)
        ELSE c.start_amount - COALESCE(ce.principal_delta, 0)
    END
FROM trading.strategy_cycle c2
LEFT JOIN first_pos f ON f.strategy_cycle_id = c2.id
LEFT JOIN row_events re ON re.cycle_position_id = f.id
LEFT JOIN cycle_events ce ON ce.strategy_cycle_id = c2.id
WHERE c.id = c2.id;

ALTER TABLE trading.strategy_cycle ALTER COLUMN open_amount SET NOT NULL;
