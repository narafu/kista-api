-- 코인 계좌(USDT/KRW)도 원장 예수금을 담으므로 통화 중립 이름으로 개명
ALTER TABLE trading.cycle_position RENAME COLUMN usd_deposit TO deposit;
