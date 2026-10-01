-- 재무 계좌 기관·소유자 (자유 입력, 선택) — 길이는 name 컬럼(VARCHAR(50)) 기준
-- nullable 추가라 2-role 배포 backward-compat(구 이미지는 컬럼을 모름 → NULL 유지)
ALTER TABLE finance.finance_accounts ADD COLUMN institution VARCHAR(50);
ALTER TABLE finance.finance_accounts ADD COLUMN owner VARCHAR(50);
