package com.kista.domain.port.out;

import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossStockInfo;

// 공통 API — 개별 계좌 토큰 불필요, 관리자 자격증명으로 조회
public interface TossStockInfoPort {
    // GET /api/v1/stocks?symbol={ticker}
    TossStockInfo getStockInfo(Ticker ticker);
}
