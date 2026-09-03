package com.kista.admin.application.service;

import com.kista.common.TimeZones;
import com.kista.account.domain.model.Account;
import com.kista.admin.domain.model.AdminCycleStrategySummary;
import com.kista.admin.domain.model.AdminAnomalies;
import com.kista.admin.domain.model.AdminStats;
import com.kista.admin.domain.model.AppErrorLog;
import com.kista.admin.domain.model.AuditLog;
import com.kista.trading.domain.model.Order;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;
import com.kista.domain.model.strategy.Strategy;
import com.kista.admin.application.usecase.AdminQueryUseCase;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.admin.application.port.output.*;
import com.kista.account.application.port.output.AccountPort;
import com.kista.application.port.output.StrategyPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.trading.application.port.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.kista.sharedkernel.UserStatus;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AdminQueryService implements AdminQueryUseCase {

    private final UserPort userPort;
    private final AccountPort accountPort;
    private final OrderPort orderPort;
    private final AuditLogPort auditLogPort;
    private final StrategyPort strategyPort;
    private final PrivacyTradePort privacyTradePort;
    private final AppErrorLogPort appErrorLogPort;

    @Override
    public AdminStats getStats() {
        // 상태별 카운트를 단일 GROUP BY 쿼리로 조회 (countAll+countByStatus×3 직렬 호출 대체)
        Map<UserStatus, Long> byStatus = userPort.countGroupByStatus();
        long pendingCount = byStatus.getOrDefault(UserStatus.PENDING, 0L);
        long activeCount = byStatus.getOrDefault(UserStatus.ACTIVE, 0L);
        long rejectedCount = byStatus.getOrDefault(UserStatus.REJECTED, 0L);
        long totalUsers = pendingCount + activeCount + rejectedCount;
        long totalAccounts = accountPort.countAll();
        return new AdminStats(totalUsers, pendingCount, activeCount, rejectedCount, totalAccounts);
    }

    @Override
    public List<Account> listAccounts(LocalDate from, LocalDate to) {
        List<Account> all = accountPort.findAll();
        if (from == null && to == null) return all;
        return all.stream()
                .filter(a -> {
                    if (a.createdAt() == null) return true;
                    LocalDate d = a.createdAt().atZone(TimeZones.KST).toLocalDate();
                    return (from == null || !d.isBefore(from))
                        && (to   == null || !d.isAfter(to));
                })
                .toList();
    }

    @Override
    public List<Order> listTrades(LocalDate from, LocalDate to) {
        // from 미지정 시 기본값: 최근 30일 — orders 테이블 전체 로드 방지
        LocalDate f = from != null ? from : LocalDate.now(TimeZones.KST).minusDays(30);
        LocalDate t = to   != null ? to   : LocalDate.now(TimeZones.KST);
        return orderPort.findAll(f, t);
    }

    @Override
    public List<AuditLog> listAuditLogs(Instant from, Instant to) {
        if (from == null && to == null) return auditLogPort.findAll();
        Instant f = from != null ? from : Instant.EPOCH;
        Instant t = to   != null ? to   : Instant.now();
        return auditLogPort.findAll(f, t);
    }

    @Override
    public AdminAnomalies getAnomalies(int inactiveDays, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(TimeZones.KST);
        LocalDate rangeFrom = from != null ? from : today.minusDays(inactiveDays);
        LocalDate rangeTo   = to   != null ? to   : today;
        List<Account> allAccounts = accountPort.findAll();

        // 배치 조회: 모든 계좌의 전략을 한 번에 조회 (N+1 방지)
        Map<UUID, List<Strategy>> strategiesByAccountId = strategyPort.findByAccountIds(
                allAccounts.stream().map(Account::id).collect(Collectors.toSet()));

        // PAUSED 전략이 있는 계좌
        List<Account> pausedAccounts = allAccounts.stream()
                .filter(a -> strategiesByAccountId.getOrDefault(a.id(), List.of()).stream()
                        .anyMatch(Strategy::isPaused))
                .toList();

        // 범위 내 거래 있는 accountId 집합 (distinct만 필요 → 별도 쿼리로 최소 데이터 로드)
        Set<UUID> activeAccountIds = new HashSet<>(
                orderPort.findDistinctAccountIdsByTradeDateBetween(rangeFrom, rangeTo));

        // ACTIVE 전략이 있지만 범위 내 거래 없는 계좌
        List<Account> inactiveAccounts = allAccounts.stream()
                .filter(a -> strategiesByAccountId.getOrDefault(a.id(), List.of()).stream()
                        .anyMatch(Strategy::isActive))
                .filter(a -> !activeAccountIds.contains(a.id()))
                .toList();

        return new AdminAnomalies(pausedAccounts, inactiveAccounts);
    }

    @Override
    public Map<UUID, AdminCycleStrategySummary> getStrategySummariesByCycleIds(Set<UUID> cycleIds) {
        return strategyPort.findSummariesByCycleIds(cycleIds);
    }

    @Override
    public List<PrivacyTradeBaseView> listPrivacyBases(Integer days) {
        // days==null → 전체(EPOCH부터). 그 외 KST 기준 최근 N일 발행분 (release_date는 KST 발행일 원본)
        LocalDate fromReleaseDate = days == null
                ? LocalDate.EPOCH
                : LocalDate.now(TimeZones.KST).minusDays(days);
        return privacyTradePort.findBasesFromTradeDate(fromReleaseDate);
    }

    @Override
    public List<Strategy> listStrategies(UUID accountId) {
        return strategyPort.findByAccountId(accountId);
    }

    @Override
    public Map<UUID, List<Strategy>> listStrategiesByAccountIds(Set<UUID> accountIds) {
        return strategyPort.findByAccountIds(accountIds);
    }

    @Override
    public List<Order> listStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate) {
        requireStrategyOwnedByAccount(accountId, strategyId);
        return orderPort.findByStrategyId(strategyId, tradeDate, tradeDate);
    }

    @Override
    public List<LocalDate> listStrategyTradeDates(UUID accountId, UUID strategyId) {
        requireStrategyOwnedByAccount(accountId, strategyId);
        return orderPort.findTradeDatesByStrategyId(strategyId);
    }

    // 경로 계층 정합성 검증 — 전략이 없거나 다른 계좌 소속이면 NoSuchElementException(→404)
    private void requireStrategyOwnedByAccount(UUID accountId, UUID strategyId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        if (!strategy.accountId().equals(accountId)) {
            throw new NoSuchElementException("전략이 해당 계좌에 속하지 않습니다");
        }
    }

    @Override
    public Optional<Account> findAccount(UUID accountId) {
        // 단일 계좌 조회 — 전체 계좌 풀스캔 없이 ID 기반 직접 조회
        return accountPort.findById(accountId);
    }

    @Override
    public List<AppErrorLog> listErrorLogs(int limit) {
        return appErrorLogPort.findRecent(limit);
    }

    @Override
    public List<AppErrorLog> listErrorLogs(int limit, Instant from, Instant to) {
        return appErrorLogPort.findRecent(limit, from, to);
    }

    @Override
    @Transactional
    public void deleteErrorLog(UUID id) {
        appErrorLogPort.softDelete(id);
    }
}
