package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminCycleStrategySummary;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.model.admin.AuditLog;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBaseView;
import com.kista.domain.model.strategy.Strategy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// 관리자 조회 전용 UseCase — 통계, 계좌·거래·감사·이상 조회 통합
public interface AdminQueryUseCase {
    AdminStats getStats();
    List<Account> listAccounts(LocalDate from, LocalDate to);   // null = 전체
    List<Order> listTrades(LocalDate from, LocalDate to);       // null = 전체
    List<AuditLog> listAuditLogs(Instant from, Instant to);     // null = 전체
    AdminAnomalies getAnomalies(int inactiveDays, LocalDate from, LocalDate to);  // from/to null = inactiveDays 기준

    // PRIVACY 기준 매매표 목록 — days==null 이면 전체, 그 외 최근 N일
    List<PrivacyTradeBaseView> listPrivacyBases(Integer days);

    // strategy_cycle.id → strategyId + strategy.type 배치 조회 (관리자 거래내역 전략 식별용)
    Map<UUID, AdminCycleStrategySummary> getStrategySummariesByCycleIds(Set<UUID> cycleIds);

    // 계좌 선택 이후 전략 선택 UI용 목록
    List<Strategy> listStrategies(UUID accountId);

    // 여러 계좌 ID → 전략 목록 배치 조회 (관리자 계좌 목록 전략 표시용)
    Map<UUID, List<Strategy>> listStrategiesByAccountIds(Set<UUID> accountIds);

    // 선택한 전략의 특정 거래일 주문 목록 조회
    List<Order> listStrategyOrders(UUID strategyId, LocalDate tradeDate);

    // 선택한 전략의 distinct 거래일 목록 조회 (관리자 주문 보정 거래일 드롭다운용)
    List<LocalDate> listStrategyTradeDates(UUID strategyId);

    // 단일 계좌 조회 — listStrategyOrders 전용 (전체 풀스캔 불필요)
    Optional<Account> findAccount(UUID accountId);
}
