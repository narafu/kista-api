package com.kista.tradingcore.trading.application.event;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

import java.math.BigDecimal;
import java.util.UUID;

// 소액 시드로 새 사이클을 열 수 없어 전략을 자동 일시정지했음을 사용자에게 알리는 이벤트
public record StrategyAutoPausedEvent(UUID userId,                // 알림 수신 사용자
                                      UUID accountId,             // 대상 계좌
                                      String accountNickname,     // 알림 문구용 계좌 별칭
                                      StrategyType strategyType,  // 전략 유형
                                      StrategyTicker ticker,      // 종목
                                      BigDecimal seed,            // 다음 사이클 시드(USD)
                                      BigDecimal price) {}        // 1주 가격(USD)
