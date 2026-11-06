package com.kista.tradingcore.trading.application.event;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

import java.util.UUID;

// 사이클 종료 뒤 다음 사이클을 여는 중 오류가 나 전략을 자동 일시정지했음을 사용자에게 알리는 이벤트
public record CycleRotationFailedEvent(UUID userId,                // 알림 수신 사용자
                                       UUID accountId,             // 대상 계좌
                                       String accountNickname,     // 알림 문구용 계좌 별칭
                                       StrategyType strategyType,  // 전략 유형
                                       StrategyTicker ticker) {}   // 종목
