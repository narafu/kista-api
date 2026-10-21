-- 원장 보유수량을 소수(크립토)까지 담도록 넓힌다. trading-core 단독 스키마라 root expand/contract 제약 밖.
-- 주의: 이후 구 이미지(int holdings 엔티티)는 ddl-auto validate에서 기동 실패 — 이미지 롤백 불가(V8·V9와 동일).
ALTER TABLE trading.cycle_position ALTER COLUMN holdings TYPE numeric(20,8);
