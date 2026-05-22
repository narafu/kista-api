-- holdings = 0 인 경우 avg_price가 null 가능 — FIDA가 보유 없을 때 null 전송
ALTER TABLE privacy_trades_master ALTER COLUMN avg_price DROP NOT NULL;
