package com.kista.domain.port.out;

import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.order.TradeHistory;

import java.time.LocalDate;
import java.util.List;

public interface TradeHistoryPort {
    void save(TradeHistory h);
    List<TradeHistory> findBy(LocalDate from, LocalDate to, Ticker ticker);
    // 기간 내 전체 계좌 거래 내역 조회 (ticker 필터 없음) — 관리자용
    List<TradeHistory> findAll(LocalDate from, LocalDate to);
}
