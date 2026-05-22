-- strategies 테이블에 배수(multiple) 컬럼 추가 — 기본값 1.0, 소수 첫째자리
ALTER TABLE strategies ADD COLUMN multiple NUMERIC(4, 1) NOT NULL DEFAULT 1.0;
