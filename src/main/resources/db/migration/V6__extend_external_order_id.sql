-- external_order_id 컬럼 길이 확장 (VARCHAR(50) → VARCHAR(255))
-- Toss orderId 등 브로커 주문번호가 50자를 초과하는 케이스 대응
ALTER TABLE orders ALTER COLUMN external_order_id TYPE VARCHAR(255) USING external_order_id::text;
