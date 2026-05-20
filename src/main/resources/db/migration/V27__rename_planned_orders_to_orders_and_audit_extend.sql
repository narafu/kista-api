-- Phase 1: accounts.broker
ALTER TABLE accounts ADD COLUMN broker VARCHAR(20) NOT NULL DEFAULT 'KIS';

-- Phase 2: planned_orders → orders
ALTER TABLE planned_orders RENAME TO orders;
ALTER INDEX idx_planned_orders_account_date_status RENAME TO idx_orders_account_date_status;
UPDATE orders SET status = 'PLANNED' WHERE status = 'PENDING';
UPDATE orders SET status = 'PLACED'  WHERE status = 'EXECUTED';
ALTER TABLE orders ALTER COLUMN status SET DEFAULT 'PLANNED';
ALTER TABLE orders RENAME COLUMN order_id TO kis_order_id;

-- Phase 3: BaseAuditEntity - audit column expansion
ALTER TABLE orders ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE kis_tokens ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
