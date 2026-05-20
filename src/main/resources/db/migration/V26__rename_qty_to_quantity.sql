ALTER TABLE trade_histories       RENAME COLUMN qty TO quantity;
ALTER TABLE portfolio_snapshots   RENAME COLUMN qty TO holdings;
ALTER TABLE planned_orders        RENAME COLUMN qty TO quantity;
ALTER TABLE privacy_trades_master RENAME COLUMN qty TO holdings;
ALTER TABLE privacy_trades_detail RENAME COLUMN qty TO quantity;
