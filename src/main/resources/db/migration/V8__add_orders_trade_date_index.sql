-- V8: Add index on orders.trade_date for admin trade history queries
CREATE INDEX idx_orders_trade_date ON orders(trade_date);
