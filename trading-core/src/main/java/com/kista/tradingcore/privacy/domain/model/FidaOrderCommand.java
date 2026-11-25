package com.kista.tradingcore.privacy.domain.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kista.shared.kernel.StrategyTicker;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FidaOrderCommand(
        @JsonAlias("tradeDate") LocalDate releaseDate, // FIDA 발행일 원본 (KST) — 거래일 아님
        StrategyTicker ticker,
        BigDecimal currentCycleStart,
        BigDecimal currentCycleRealizedPnl,
        @Nullable BigDecimal avgPrice,
        int holdings,
        List<FidaPlannedOrder> orders
) {}
