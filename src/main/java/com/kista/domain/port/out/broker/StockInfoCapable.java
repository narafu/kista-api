package com.kista.domain.port.out.broker;

import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossStockInfo;

// 종목 정보 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface StockInfoCapable {
    TossStockInfo getStockInfo(Ticker ticker);
}
