package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
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

    // strategy_cycle.id → strategy.type 배치 조회 (관리자 거래내역 전략 타입 표시용)
    Map<UUID, Strategy.Type> getStrategyTypesByCycleIds(Set<UUID> cycleIds);

    // 계좌 선택 이후 전략 선택 UI용 목록
    List<Strategy> listStrategies(UUID accountId);
}
