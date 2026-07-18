-- orders.trade_date 기준 변경: UTC(=US 거래일) → KST 거래일 (+1일 균일 shift)
-- 소프트 삭제 행 포함 전체 갱신 — 모든 행이 단일 기준을 유지해야 함
UPDATE orders SET trade_date = trade_date + 1;

COMMENT ON COLUMN orders.trade_date IS 'KST 거래일 — 매매가 실행·정산되는 KST 아침이 속한 날';
