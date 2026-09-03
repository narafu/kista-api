package com.kista.broker.application.port.output;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.domain.model.toss.TossStockInfo;

// 종목 정보 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface StockInfoPort {
    TossStockInfo getStockInfo(StrategyTicker ticker);
}
