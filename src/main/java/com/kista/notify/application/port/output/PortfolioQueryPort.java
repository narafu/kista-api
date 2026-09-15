package com.kista.notify.application.port.output;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// trading-core stats.PortfolioUseCase를 notify(TelegramBotService)가 직접 참조하지 않도록
// 필요한 조회만 담은 전용 포트
public interface PortfolioQueryPort {
    PortfolioCurrentView getCurrent(UUID userId);
    List<PortfolioOrderView> getHistory(UUID userId, LocalDate from, LocalDate to, StrategyTicker ticker);

    // 현재 포트폴리오 현황 — CyclePositionHistoryEntry의 notify 소비 필드만 narrowing
    record PortfolioCurrentView(StrategyTicker ticker, int holdings, BigDecimal avgPrice,
                                 BigDecimal usdDeposit, BigDecimal closingPrice) {}

    // 거래 내역 1건 — Order의 notify 소비 필드만 narrowing
    record PortfolioOrderView(LocalDate tradeDate, StrategyTicker ticker, OrderDirection direction,
                               OrderType orderType, Integer quantity, BigDecimal price) {}
}
