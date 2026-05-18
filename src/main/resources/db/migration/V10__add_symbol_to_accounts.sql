-- 계좌별 거래 종목 설정 (기존 계좌는 SOXL/AMS 기본값 적용)
ALTER TABLE accounts
    ADD COLUMN symbol        VARCHAR(20) NOT NULL DEFAULT 'SOXL',
    ADD COLUMN exchange_code VARCHAR(20) NOT NULL DEFAULT 'AMS';
