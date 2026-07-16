package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminCycleStrategySummary;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.order.Order;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

// 어드민 거래 내역 응답 DTO
public record AdminTradeResponse(
        @Schema(description = "주문 고유 ID")
        UUID id,
        @Schema(description = "계좌 소유자 사용자 ID")
        UUID userId,
        @Schema(description = "소속 계좌 ID")
        UUID accountId,
        @Schema(description = "소속 전략 ID (조회 실패 시 null)")
        UUID strategyId,
        @Schema(description = "계좌 소유자 닉네임")
        String ownerNickname,    // 계좌 소유자 닉네임
        @Schema(description = "전략 종류 (조회 실패 시 null)", example = "INFINITE")
        String strategyType,     // INFINITE | PRIVACY (null 가능)
        @Schema(description = "거래일 (KST)")
        LocalDate tradeDate,
        @Schema(description = "거래 종목", example = "SOXL")
        String ticker,
        @Schema(description = "매매 방향", example = "BUY")
        String direction,        // BUY | SELL
        @Schema(description = "주문 유형", example = "LOC")
        String orderType,        // LOC | MOC | LIMIT
        @Schema(description = "접수 시점", example = "AT_OPEN")
        String timing,           // AT_OPEN | AT_CLOSE
        @Schema(description = "주문 수량")
        int quantity,
        @Schema(description = "주문 가격")
        BigDecimal price,
        @Schema(description = "주문 상태", example = "FILLED")
        String status,           // PLACED | FILLED | FAILED
        @Schema(description = "브로커 측 주문 ID")
        String externalOrderId,
        @Schema(description = "체결 수량 (미체결 시 null)")
        Integer filledQuantity,
        @Schema(description = "체결 가격 (미체결 시 null)")
        BigDecimal filledPrice
) {
    public static AdminTradeResponse from(Order t, Map<UUID, Account> accountMap,
                                          Map<UUID, AdminUserView> userMap,
                                          Map<UUID, AdminCycleStrategySummary> strategySummaryMap) {
        // accountId → userId → nickname 순서로 역방향 조회
        Account account = t.accountId() != null ? accountMap.get(t.accountId()) : null;
        UUID userId = account != null ? account.userId() : null;
        AdminUserView user = userId != null ? userMap.get(userId) : null;
        String nickname = user != null ? user.nickname() : "(알 수 없음)";
        AdminCycleStrategySummary strategySummary = t.strategyCycleId() != null ? strategySummaryMap.get(t.strategyCycleId()) : null;
        return new AdminTradeResponse(
                t.id(), userId, t.accountId(),
                strategySummary != null ? strategySummary.strategyId() : null,
                nickname,
                strategySummary != null ? strategySummary.strategyType().name() : null,
                t.tradeDate(), t.ticker().name(),
                t.direction().name(), t.orderType().name(), t.timing().name(),
                t.quantity(), t.price(), t.status().name(),
                t.externalOrderId(), t.filledQuantity(), t.filledPrice());
    }
}
