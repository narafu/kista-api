package com.kista.tradingcore.trading.application.usecase;

// 전략 원장 대사 — 실보유·원장 괴리를 관리자에게 알린다(매매 경로 미사용)
public interface LedgerReconciliationUseCase {

    void reconcileAll();
}
