package com.kista.tradingcore.trading.application.usecase;

import java.util.UUID;

// 회원 탈퇴 trading-core 정리 2단계 — TradingUserCascadeListener가 순서대로 따로 호출한다(각 메서드가 자기 트랜잭션 경계를 연다)
public interface UserTradingDataEraseUseCase {
    void cancelOpenOrders(UUID userId); // 1단계: 계좌마다 전략 PAUSED + 증권사 미체결 취소(트랜잭션 밖)
    void erase(UUID userId);            // 2단계: 사이클·전략·계좌 정리 + 알림 프로필 tombstone(새 트랜잭션)
}
