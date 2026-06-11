package com.kista.application.service.admin;

import com.kista.common.TradeDateConverter;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.model.admin.AuditLog;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBaseView;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
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
    public List<Account> listAccounts() {
        return accountPort.findAll();
    }

    @Override
    public List<Order> listTrades() {
        // 최근 30일 전체 계좌 거래 내역 조회
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        return orderPort.findAll(from, to);
    }

    @Override
    public List<AuditLog> listAuditLogs() {
        return auditLogPort.findAll(); // 최신순 100건
    }

    @Override
    public AdminAnomalies getAnomalies() {
        LocalDate today = LocalDate.now();
        List<Account> allAccounts = accountPort.findAll();

        // PAUSED 전략이 있는 계좌
        List<Account> pausedAccounts = allAccounts.stream()
                .filter(a -> strategyPort.findByAccountId(a.id()).stream()
                        .anyMatch(s -> s.status() == Strategy.Status.PAUSED))
                .toList();

        // 최근 7일 거래 있는 accountId 집합
        Set<UUID> activeAccountIds = orderPort.findAll(today.minusDays(7), today)
                .stream().map(Order::accountId).collect(Collectors.toSet());

        // ACTIVE 전략이 있지만 7일 내 거래 없는 계좌
        List<Account> inactiveAccounts = allAccounts.stream()
                .filter(a -> strategyPort.findByAccountId(a.id()).stream()
                        .anyMatch(s -> s.status() == Strategy.Status.ACTIVE))
                .filter(a -> !activeAccountIds.contains(a.id()))
                .toList();

        return new AdminAnomalies(pausedAccounts, inactiveAccounts);
    }

    @Override
    public List<PrivacyTradeBaseView> listPrivacyBases(Integer days) {
        // days==null → 전체(EPOCH부터). 그 외 KST 기준 최근 N일을 UTC 거래일 경계로 변환
        LocalDate fromUtc = days == null
                ? LocalDate.EPOCH
                : TradeDateConverter.toUtc(LocalDate.now().minusDays(days));
        return privacyTradePort.findBasesFromTradeDate(fromUtc);
    }
}
