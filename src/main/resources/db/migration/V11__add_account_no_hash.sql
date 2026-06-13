-- 계좌번호 전역 중복 체크용 HMAC-SHA256 해시 컬럼 추가
-- AES-256-GCM은 비결정론적(IV 랜덤)이므로 DB UNIQUE 제약 불가 → 해시 컬럼으로 대체
-- 기존 레코드는 NULL 허용 (backfill은 별도 작업), partial index로 신규 등록부터만 유니크 보장
ALTER TABLE accounts ADD COLUMN account_no_hash VARCHAR(64);

-- NULL hash 행은 제외 → V11 이전 기존 레코드와 신규 데이터 공존 가능
CREATE UNIQUE INDEX uq_accounts_account_no_hash
    ON accounts (account_no_hash)
    WHERE deleted_at IS NULL AND account_no_hash IS NOT NULL;
