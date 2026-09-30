package com.kista.admin.application.port.output;

import com.kista.contract.trading.OrderResponse;
import com.kista.contract.trading.StrategySummaryResponse;
import com.kista.contract.trading.StrategyResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// admin이 정의하는 trading-core 조회 포트 — TradingQueryHttpAdapter가 내부 API로 구현
public interface TradingQueryPort {
    List<OrderResponse> findAllOrders(LocalDate from, LocalDate to);
    List<UUID> findDistinctAccountIds(LocalDate from, LocalDate to);
    List<StrategyResponse> findStrategiesByAccountId(UUID accountId);
    Map<UUID, List<StrategyResponse>> findStrategiesByAccountIds(Set<UUID> accountIds);
    Map<UUID, StrategySummaryResponse> findStrategySummariesByCycleIds(Set<UUID> cycleIds);
    List<OrderResponse> findStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate);
    List<LocalDate> findStrategyTradeDates(UUID accountId, UUID strategyId);
}
