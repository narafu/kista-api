package com.kista.notify.application.port.output;

import com.kista.contract.stats.PortfolioCurrentResponse;
import com.kista.contract.stats.PortfolioOrderResponse;
import com.kista.sharedkernel.StrategyTicker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// trading-core stats.PortfolioUseCase를 notify(TelegramBotService)가 직접 참조하지 않도록
// 필요한 조회만 담은 전용 포트
public interface PortfolioQueryPort {
    PortfolioCurrentResponse getCurrent(UUID userId);
    List<PortfolioOrderResponse> getHistory(UUID userId, LocalDate from, LocalDate to, StrategyTicker ticker);
}
