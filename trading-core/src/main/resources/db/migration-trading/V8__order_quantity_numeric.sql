-- 코인 소수 수량(업비트 8자리) 수용 — 주문·DCA 회차 수량을 integer → numeric(20,8)로 넓힌다.
-- trading-core 단독 스키마라 root expand/contract 제약 밖(kista-trading은 stop-first 단일 인스턴스).
-- 주의: 이 마이그레이션 이후 구 이미지(int 엔티티)는 ddl-auto validate에서 기동 실패 — 이미지 롤백 불가.
ALTER TABLE trading.orders ALTER COLUMN quantity TYPE numeric(20,8);
ALTER TABLE trading.orders ALTER COLUMN filled_quantity TYPE numeric(20,8);
ALTER TABLE trading.dca_execution ALTER COLUMN quantity TYPE numeric(20,8);
