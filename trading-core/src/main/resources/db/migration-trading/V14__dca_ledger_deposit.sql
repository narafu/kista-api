-- DCA 원장 예수금 전환 — DCA도 시드 점유 전략(start_amount 기준 통계)으로 바뀐다.
-- 기존 DCA 사이클의 원장 예수금은 이미 0이고(체결이 매번 0으로 기록), 통계 원금은 체결 흐름 기준(초기 원가 + 누적 매수 − 누적 매도)이었다.
-- 손익이 유지되도록 start_amount에 누적 순매수 체결금액(매수 − 매도)을 더한다(원장 예수금 0 유지, 보유·평단 불변).
-- 적용 시점 운영 DCA에는 매도 체결이 없어 수익률도 같다(매도가 있으면 원금이 매도분만큼 줄어 수익률 분모가 달라진다).
UPDATE trading.strategy_cycle c
SET start_amount = c.start_amount + f.net_bought
FROM (
    SELECT o.strategy_cycle_id,
           SUM(CASE WHEN o.direction = 'BUY' THEN o.filled_quantity * o.filled_price
                    ELSE -o.filled_quantity * o.filled_price END) AS net_bought
    FROM trading.orders o
    WHERE o.filled_quantity > 0 AND o.filled_price IS NOT NULL
    GROUP BY o.strategy_cycle_id
) f
WHERE f.strategy_cycle_id = c.id
  AND c.strategy_id IN (SELECT id FROM trading.strategy WHERE type = 'DCA');
