-- 같은 계좌·종목의 활성(미보관·미삭제) 전략은 하나뿐 — 등록 검증(check-then-act)의 경합을 DB가 최종 차단한다
CREATE UNIQUE INDEX uq_strategy_account_ticker_active ON trading.strategy (account_id, ticker)
    WHERE deleted_at IS NULL AND status <> 'ARCHIVED';
