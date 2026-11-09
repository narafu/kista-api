package com.kista.tradingcore.stats.application.usecase;

import com.kista.tradingcore.stats.domain.model.PortfolioOrder;
import com.kista.tradingcore.stats.domain.model.PortfolioPosition;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PortfolioUseCase {
    List<PortfolioPosition> getCurrent(UUID userId);
    List<PortfolioOrder> getHistory(UUID userId, LocalDate from, LocalDate to);
}
