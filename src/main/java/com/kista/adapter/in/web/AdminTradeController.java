package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.AdminUserUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/trades")
@RequiredArgsConstructor
public class AdminTradeController {

    private final AdminQueryUseCase adminQuery;  // 거래·계좌 조회 (최근 30일 전체, accountId → userId 매핑)
    private final AdminUserUseCase adminUser;   // userId → nickname 매핑용

    // 전체 거래 내역 목록 — 일괄 조회로 N+1 방지
    @GetMapping
    public List<AdminTradeResponse> listTrades(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Map<UUID, Account> accountMap = adminQuery.listAccounts(null, null).stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
        Map<UUID, AdminUserView> userMap = adminUser.listAll(null, null).stream()
                .collect(Collectors.toMap(AdminUserView::id, Function.identity()));
        List<Order> trades = adminQuery.listTrades(from, to);
        Set<UUID> cycleIds = trades.stream()
                .map(Order::strategyCycleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Strategy.Type> strategyTypeMap = adminQuery.getStrategyTypesByCycleIds(cycleIds);
        return trades.stream()
                .map(t -> AdminTradeResponse.from(t, accountMap, userMap, strategyTypeMap))
                .toList();
    }

    // 거래 내역 응답 DTO — package-private record (같은 패키지 AdminAnomaliesController에서 재사용 가능)
    record AdminTradeResponse(
            UUID id,
            UUID userId,
            String ownerNickname,    // 계좌 소유자 닉네임
            String strategyType,     // INFINITE | PRIVACY (null 가능)
            LocalDate tradeDate,
            String ticker,
            String direction,        // BUY | SELL
            String orderType,        // LOC | MOC | LIMIT
            int quantity,
            BigDecimal price,
            String status            // PLACED | FILLED | FAILED
    ) {
        static AdminTradeResponse from(Order t, Map<UUID, Account> accountMap,
                                       Map<UUID, AdminUserView> userMap,
                                       Map<UUID, Strategy.Type> strategyTypeMap) {
            // accountId → userId → nickname 순서로 역방향 조회
            Account account = t.accountId() != null ? accountMap.get(t.accountId()) : null;
            UUID userId = account != null ? account.userId() : null;
            AdminUserView user = userId != null ? userMap.get(userId) : null;
            String nickname = user != null ? user.nickname() : "(알 수 없음)";
            Strategy.Type strategyType = t.strategyCycleId() != null ? strategyTypeMap.get(t.strategyCycleId()) : null;
            return new AdminTradeResponse(
                    t.id(), userId, nickname,
                    strategyType != null ? strategyType.name() : null,
                    t.tradeDate(), t.ticker().name(),
                    t.direction().name(), t.orderType().name(),
                    t.quantity(), t.price(), t.status().name());
        }
    }
}
