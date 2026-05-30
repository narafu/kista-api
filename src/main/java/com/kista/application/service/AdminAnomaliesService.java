package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.AdminAnomaliesUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.TradingCyclePort;
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
public class AdminAnomaliesService implements AdminAnomaliesUseCase {

    private final OrderPort orderPort;
    private final AccountPort accountPort;
    private final TradingCyclePort cyclePort;

    @Override
    public AdminAnomalies getAnomalies() {
        LocalDate today = LocalDate.now();

        List<Account> allAccounts = accountPort.findAll();

        // PAUSED 사이클이 있는 계좌
        List<Account> pausedAccounts = allAccounts.stream()
                .filter(a -> cyclePort.findByAccountId(a.id()).stream()
                        .anyMatch(c -> c.status() == TradingCycle.Status.PAUSED))
                .toList();

        // 최근 7일 거래 있는 accountId 집합
        Set<UUID> activeAccountIds = orderPort.findAll(today.minusDays(7), today)
                .stream().map(Order::accountId).collect(Collectors.toSet());

        // ACTIVE 사이클이 있지만 7일 내 거래 없는 계좌
        List<Account> inactiveAccounts = allAccounts.stream()
                .filter(a -> cyclePort.findByAccountId(a.id()).stream()
                        .anyMatch(c -> c.status() == TradingCycle.Status.ACTIVE))
                .filter(a -> !activeAccountIds.contains(a.id()))
                .toList();

        return new AdminAnomalies(pausedAccounts, inactiveAccounts);
    }
}
