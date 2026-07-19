package com.kista.domain.port.in;

public interface SyncMarketIndexPricesUseCase {
    // ETF 벤치마크 지수 종가를 조회해 저장
    void syncAndSave();
}
