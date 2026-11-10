-- KIS·토스·메리츠 기존 계좌 API 키 만료일 추정(등록일 + 1년) — 키 발급일이 아니라 추정치, 사용자가 계좌 설정에서 정정
UPDATE trading.accounts
SET api_key_expires_at = created_at + interval '1 year'
WHERE broker IN ('KIS', 'TOSS', 'MERITZ')
  AND api_key_expires_at IS NULL;

COMMENT ON COLUMN trading.accounts.api_key_expires_at IS 'API 키 만료 시각 — 업비트는 API 조회, KIS·토스·메리츠는 추정(+1년) 또는 사용자 입력, 그 외 NULL';
