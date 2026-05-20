package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.TradeHistory;
import com.kista.domain.port.in.AdminAnomaliesUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.TradeHistoryPort;
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

    private final TradeHistoryPort tradeHistoryPort;
    private final AccountRepository accountRepository;

    @Override
    public AdminAnomalies getAnomalies() {
        LocalDate today = LocalDate.now();

        // 최근 30일 전체 거래 중 FAILED 필터
        List<TradeHistory> failedTrades = tradeHistoryPort.findAll(today.minusDays(30), today)
                .stream()
                .filter(t -> t.status() == Order.OrderStatus.FAILED)
                .toList();

        List<Account> allAccounts = accountRepository.findAll();

        // 전략 PAUSED 계좌
        List<Account> pausedAccounts = allAccounts.stream()
                .filter(a -> a.strategyStatus() == Account.StrategyStatus.PAUSED)
                .toList();

        // 최근 7일 거래 있는 accountId 집합
        Set<UUID> activeAccountIds = tradeHistoryPort.findAll(today.minusDays(7), today)
                .stream()
                .map(TradeHistory::accountId)
                .collect(Collectors.toSet());

        // ACTIVE 전략이지만 7일 내 거래 없는 계좌
        List<Account> inactiveAccounts = allAccounts.stream()
                .filter(a -> a.strategyStatus() == Account.StrategyStatus.ACTIVE)
                .filter(a -> !activeAccountIds.contains(a.id()))
                .toList();

        return new AdminAnomalies(failedTrades, pausedAccounts, inactiveAccounts);
    }
}
