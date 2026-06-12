-- KIS 전용 주문번호 컬럼을 브로커 중립 명칭으로 변경 (토스증권 등 복수 브로커 지원 준비)
ALTER TABLE orders RENAME COLUMN kis_order_id TO external_order_id;
ALTER TABLE orders ALTER COLUMN external_order_id TYPE VARCHAR(50);
