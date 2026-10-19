package com.kista.tradingcore.stats.application.usecase;

import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators;

// 코인 시장 지표 조회 (김프·테더 프리미엄·펀딩비·OI·밸류에이션·롱숏 비율)
public interface CryptoMarketIndicatorUseCase {

    // days: 1~200, 범위 밖이면 IllegalArgumentException
    CryptoMarketIndicators get(int days);
}
