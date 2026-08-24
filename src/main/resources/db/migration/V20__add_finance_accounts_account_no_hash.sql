-- 계좌번호 전역 중복 등록 방지: 결정론적 HMAC-SHA256 해시 컬럼 추가 (AES 암호문은 비결정론적이라 직접 unique 불가)
ALTER TABLE finance.finance_accounts ADD COLUMN account_no_hash VARCHAR(64);

-- 소프트 삭제된 행·계좌번호 미입력 행은 제외한 partial unique index
CREATE UNIQUE INDEX uq_finance_accounts_account_no_hash
    ON finance.finance_accounts(account_no_hash)
    WHERE deleted_at IS NULL AND account_no_hash IS NOT NULL;
