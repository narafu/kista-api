-- 기존 V1 데이터 하위호환: account_id NULL 허용
ALTER TABLE trade_histories     ADD COLUMN account_id UUID REFERENCES accounts(id);
ALTER TABLE portfolio_snapshots ADD COLUMN account_id UUID REFERENCES accounts(id);
ALTER TABLE kis_tokens          ADD COLUMN account_id UUID REFERENCES accounts(id);
