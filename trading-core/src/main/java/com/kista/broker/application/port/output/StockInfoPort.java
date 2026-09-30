package com.kista.broker.application.port.output;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.domain.model.BrokerStockInfo;

// 종목 정보 조회 (현재 Toss만 구현) — 공통 API, Account 토큰 불필요. 벤더 중립 BrokerStockInfo 반환
public interface StockInfoPort {
    BrokerStockInfo getStockInfo(StrategyTicker ticker);
}
