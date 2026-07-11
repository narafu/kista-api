package com.kista.application.service.admin;

import com.kista.common.TimeZones;
import com.kista.common.TradeDateConverter;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminCycleStrategySummary;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.model.admin.AppErrorLog;
import com.kista.domain.model.admin.AuditLog;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBaseView;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        long totalUsers = userPort.countAll();
        long pendingCount = userPort.countByStatus(User.UserStatus.PENDING);
        long activeCount = userPort.countByStatus(User.UserStatus.ACTIVE);
        long rejectedCount = userPort.countByStatus(User.UserStatus.REJECTED);
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
        LocalDate f = from != null ? from : LocalDate.EPOCH;
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

        // PAUSED 전략이 있는 계좌
        List<Account> pausedAccounts = allAccounts.stream()
                .filter(a -> strategyPort.findByAccountId(a.id()).stream()
                        .anyMatch(Strategy::isPaused))
                .toList();

        // 범위 내 거래 있는 accountId 집합
        Set<UUID> activeAccountIds = orderPort.findAll(rangeFrom, rangeTo)
                .stream().map(Order::accountId).collect(Collectors.toSet());

        // ACTIVE 전략이 있지만 범위 내 거래 없는 계좌
        List<Account> inactiveAccounts = allAccounts.stream()
                .filter(a -> strategyPort.findByAccountId(a.id()).stream()
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
        // days==null → 전체(EPOCH부터). 그 외 KST 기준 최근 N일을 UTC 거래일 경계로 변환
        LocalDate fromUtc = days == null
                ? LocalDate.EPOCH
                : TradeDateConverter.toUtc(LocalDate.now(TimeZones.KST).minusDays(days));
        return privacyTradePort.findBasesFromTradeDate(fromUtc);
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
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId); // 없으면 NoSuchElementException → 404
        // 경로 계층 정합성 검증 — 다른 계좌의 전략 조회 차단
        if (!strategy.accountId().equals(accountId)) {
            throw new NoSuchElementException("전략이 해당 계좌에 속하지 않습니다");
        }
        return orderPort.findByStrategyId(strategyId, tradeDate, tradeDate);
    }

    @Override
    public List<LocalDate> listStrategyTradeDates(UUID accountId, UUID strategyId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId); // 없으면 NoSuchElementException → 404
        // 경로 계층 정합성 검증 — 다른 계좌의 전략 조회 차단
        if (!strategy.accountId().equals(accountId)) {
            throw new NoSuchElementException("전략이 해당 계좌에 속하지 않습니다");
        }
        return orderPort.findTradeDatesByStrategyId(strategyId);
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
