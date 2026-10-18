package com.kista.tradingcore.trading.application.usecase;

import java.time.LocalDateTime;

// 코인 DCA 10분 틱 실행 — 계좌별 주문 동기화 후 라운드 실행
public interface CryptoDcaExecutionUseCase {
    void runTick(LocalDateTime nowKst);
}
