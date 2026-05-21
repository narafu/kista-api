-- PrivacyTradeDetailEntity.quantity nullable 변경 반영
ALTER TABLE privacy_trades_detail ALTER COLUMN quantity DROP NOT NULL;
