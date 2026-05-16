-- exchange_code 컬럼 제거: Ticker enum이 exchangeCode를 포함하므로 DB에 별도 저장 불필요
ALTER TABLE accounts DROP COLUMN IF EXISTS exchange_code;
