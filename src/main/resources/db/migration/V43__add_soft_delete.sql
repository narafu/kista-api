-- V43: 소프트 삭제(soft delete) 지원 — deleted_at IS NULL 조건으로 논리 삭제 처리
ALTER TABLE users         ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE accounts      ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE trading_cycle ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
