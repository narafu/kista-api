-- KIS accountNo에 계좌 상품코드 통합 ("74420614" + "01" → "74420614-01")
UPDATE accounts SET account_no = account_no || '-' || kis_account_type WHERE broker = 'KIS';

-- 컬럼명을 브로커 무관 이름으로 변경
ALTER TABLE accounts RENAME COLUMN kis_account_type TO broker_account_code;

-- KIS는 accountNo에 통합됐으므로 null 처리
UPDATE accounts SET broker_account_code = NULL WHERE broker = 'KIS';
