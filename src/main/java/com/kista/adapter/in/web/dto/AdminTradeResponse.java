package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

// 어드민 거래 내역 응답 DTO
public record AdminTradeResponse(
        UUID id,
        UUID userId,
        String ownerNickname,    // 계좌 소유자 닉네임
        String strategyType,     // INFINITE | PRIVACY (null 가능)
        LocalDate tradeDate,
        String ticker,
        String direction,        // BUY | SELL
        String orderType,        // LOC | MOC | LIMIT
        String timing,           // AT_OPEN | AT_CLOSE
        int quantity,
        BigDecimal price,
        String status,           // PLACED | FILLED | FAILED
        String externalOrderId,
        Integer filledQuantity,
        BigDecimal filledPrice
) {
    public static AdminTradeResponse from(Order t, Map<UUID, Account> accountMap,
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
                t.direction().name(), t.orderType().name(), t.timing().name(),
                t.quantity(), t.price(), t.status().name(),
                t.externalOrderId(), t.filledQuantity(), t.filledPrice());
    }
}
