-- trade_histories: symbol → ticker
ALTER TABLE trade_histories RENAME COLUMN symbol TO ticker;

-- planned_orders: symbol → ticker, kis_order_id → order_id
ALTER TABLE planned_orders RENAME COLUMN symbol TO ticker;
ALTER TABLE planned_orders RENAME COLUMN kis_order_id TO order_id;

-- portfolio_snapshots: symbol → ticker
ALTER TABLE portfolio_snapshots RENAME COLUMN symbol TO ticker;
