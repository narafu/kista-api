-- PrivacyTradeMasterEntity.currentCycleRealizedPnl 필드 반영 (현재 사이클 실현 수익)
ALTER TABLE privacy_trades_master
    ADD COLUMN current_cycle_realized_pnl NUMERIC(12, 4) NOT NULL DEFAULT 0;
