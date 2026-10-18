-- 업비트 계좌 — 계좌번호 개념이 없어 account_no NULL 허용, API 키 만료일(업비트 /v1/api_keys expire_at) 저장.
-- 업비트 중복 판정은 account_no_hash에 access key HMAC을 넣어 기존 uq_accounts_account_no_hash를 재사용한다.
-- trading-core 단독 스키마(root expand/contract 밖). 구 이미지는 nullable 차이를 validate하지 않아 호환.
-- 컬럼 순서 규칙 예외: ADD COLUMN은 항상 맨 뒤(deleted_at 뒤)이므로 Entity 필드도 물리 순서대로 마지막에 둔다(수용된 예외).
ALTER TABLE trading.accounts ALTER COLUMN account_no DROP NOT NULL;
ALTER TABLE trading.accounts ADD COLUMN api_key_expires_at timestamp with time zone;
COMMENT ON COLUMN trading.accounts.api_key_expires_at IS '거래소 API 키 만료 시각 — 업비트만 값, 그 외 NULL';
