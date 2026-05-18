-- V8에서 ON DELETE CASCADE 없이 추가된 FK 3개를 CASCADE로 재생성
ALTER TABLE kis_tokens DROP CONSTRAINT kis_tokens_account_id_fkey;
ALTER TABLE kis_tokens ADD CONSTRAINT kis_tokens_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE trade_histories DROP CONSTRAINT trade_histories_account_id_fkey;
ALTER TABLE trade_histories ADD CONSTRAINT trade_histories_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE portfolio_snapshots DROP CONSTRAINT portfolio_snapshots_account_id_fkey;
ALTER TABLE portfolio_snapshots ADD CONSTRAINT portfolio_snapshots_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
