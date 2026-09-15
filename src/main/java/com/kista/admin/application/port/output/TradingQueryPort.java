package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminStrategySummary;
import com.kista.admin.domain.model.AdminStrategyView;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// admin이 정의하는 trading-core 조회 포트 — TradingQueryHttpAdapter가 내부 API로 구현
public interface TradingQueryPort {
    List<AdminOrderView> findAllOrders(LocalDate from, LocalDate to);
    List<UUID> findDistinctAccountIds(LocalDate from, LocalDate to);
    List<AdminStrategyView> findStrategiesByAccountId(UUID accountId);
    Map<UUID, List<AdminStrategyView>> findStrategiesByAccountIds(Set<UUID> accountIds);
    Map<UUID, AdminStrategySummary> findStrategySummariesByCycleIds(Set<UUID> cycleIds);
    List<AdminOrderView> findStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate);
    List<LocalDate> findStrategyTradeDates(UUID accountId, UUID strategyId);
}
