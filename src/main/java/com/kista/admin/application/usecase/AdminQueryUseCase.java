package com.kista.admin.application.usecase;

import com.kista.admin.domain.model.AdminAccountView;
import com.kista.admin.domain.model.AdminAnomalies;
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminPrivacyTradeBaseView;
import com.kista.admin.domain.model.AdminStats;
import com.kista.admin.domain.model.AdminStrategySummary;
import com.kista.admin.domain.model.AdminStrategyView;
import com.kista.admin.domain.model.AppErrorLog;
import com.kista.admin.domain.model.AuditLog;

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
    List<AdminAccountView> listAccounts(LocalDate from, LocalDate to);   // null = 전체
    List<AdminOrderView> listTrades(LocalDate from, LocalDate to);       // null = 전체
    List<AuditLog> listAuditLogs(Instant from, Instant to);     // null = 전체
    AdminAnomalies getAnomalies(int inactiveDays, LocalDate from, LocalDate to);  // from/to null = inactiveDays 기준

    // PRIVACY 기준 매매표 목록 — days==null 이면 전체, 그 외 최근 N일
    List<AdminPrivacyTradeBaseView> listPrivacyBases(Integer days);

    // strategy_cycle.id → strategyId + strategy.type 배치 조회 (관리자 거래내역 전략 식별용)
    Map<UUID, AdminStrategySummary> getStrategySummariesByCycleIds(Set<UUID> cycleIds);

    // 계좌 선택 이후 전략 선택 UI용 목록
    List<AdminStrategyView> listStrategies(UUID accountId);

    // 여러 계좌 ID → 전략 목록 배치 조회 (관리자 계좌 목록 전략 표시용)
    Map<UUID, List<AdminStrategyView>> listStrategiesByAccountIds(Set<UUID> accountIds);

    // 선택한 전략의 특정 거래일 주문 목록 조회
    // accountId로 경로 계층 정합성 검증 — 전략이 해당 계좌 소속이 아니면 NoSuchElementException
    List<AdminOrderView> listStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate);

    // 선택한 전략의 distinct 거래일 목록 조회 (관리자 주문 보정 거래일 드롭다운용)
    // accountId로 경로 계층 정합성 검증 — 전략이 해당 계좌 소속이 아니면 NoSuchElementException
    List<LocalDate> listStrategyTradeDates(UUID accountId, UUID strategyId);

    // 단일 계좌 조회 — listStrategyOrders 전용 (전체 풀스캔 불필요)
    Optional<AdminAccountView> findAccount(UUID accountId);

    // 앱 에러 로그 조회 / 소프트 삭제 — AdminObservabilityController 전용
    List<AppErrorLog> listErrorLogs(int limit);
    List<AppErrorLog> listErrorLogs(int limit, Instant from, Instant to);
    void deleteErrorLog(UUID id);
}
