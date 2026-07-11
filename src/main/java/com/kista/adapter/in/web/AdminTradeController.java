package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminManualTradeCorrectionRequest;
import com.kista.adapter.in.web.dto.AdminReorderRequest;
import com.kista.adapter.in.web.dto.AdminReorderResponse;
import com.kista.adapter.in.web.dto.AdminTradeCorrectionResponse;
import com.kista.adapter.in.web.dto.AdminTradeResponse;
import com.kista.adapter.in.web.dto.ReorderTimingAvailabilityResponse;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminCycleStrategySummary;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.AdminReorderUseCase;
import com.kista.domain.port.in.AdminTradeCorrectionUseCase;
import com.kista.domain.port.in.AdminUserUseCase;
import com.kista.domain.port.out.MarketCalendarPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminTradeController {

    private final AdminQueryUseCase adminQuery;  // 거래·계좌 조회 (최근 30일 전체, accountId → userId 매핑)
    private final AdminUserUseCase adminUser;   // userId → nickname 매핑용
    private final AdminTradeCorrectionUseCase adminTradeCorrection; // 관리자 수동 체결 보정
    private final AdminReorderUseCase adminReorder;                 // 관리자 재주문
    private final MarketCalendarPort marketCalendarPort;            // 휴장일 판정

    // 전체 거래 내역 목록 — 일괄 조회로 N+1 방지
    @GetMapping("/trades")
    public List<AdminTradeResponse> listTrades(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return toResponses(adminQuery.listTrades(from, to));
    }

    @GetMapping("/accounts/{accountId}/strategies/{strategyId}/trade-dates")
    public List<LocalDate> listStrategyTradeDates(
            @PathVariable UUID accountId,
            @PathVariable UUID strategyId) {
        return adminQuery.listStrategyTradeDates(accountId, strategyId);
    }

    @GetMapping("/accounts/{accountId}/strategies/{strategyId}/orders")
    public List<AdminTradeResponse> listStrategyOrders(
            @PathVariable UUID accountId,
            @PathVariable UUID strategyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        List<Order> orders = adminQuery.listStrategyOrders(strategyId, tradeDate).stream()
                .filter(order -> accountId.equals(order.accountId()))
                .toList();
        if (orders.isEmpty()) return List.of();
        // 단일 계좌만 조회 — 전체 풀스캔 불필요
        Account account = adminQuery.findAccount(accountId)
                .orElseThrow(() -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));
        AdminUserView user = adminUser.findUser(account.userId())
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + account.userId()));
        Map<UUID, Account> accountMap = Map.of(accountId, account);
        Map<UUID, AdminUserView> userMap = Map.of(account.userId(), user);
        Set<UUID> cycleIds = orders.stream().map(Order::strategyCycleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, AdminCycleStrategySummary> strategySummaryMap = adminQuery.getStrategySummariesByCycleIds(cycleIds);
        return orders.stream().map(o -> AdminTradeResponse.from(o, accountMap, userMap, strategySummaryMap)).toList();
    }

    // 관리자 수동 체결 보정 — fills 배열 순서대로 여러 건을 원자적으로 반영
    @PostMapping("/trades/manual-fills")
    public AdminTradeCorrectionResponse correctManualFills(
            @AuthenticationPrincipal UUID adminId,
            @RequestBody @Valid AdminManualTradeCorrectionRequest request) {
        return AdminTradeCorrectionResponse.from(
                adminTradeCorrection.correctManualFills(adminId, request.toCommand()));
    }

    // 재주문 시점 가용성 조회 — UI 주문시점 셀렉터 disable 판단용
    @GetMapping("/trades/reorder-timing")
    public ReorderTimingAvailabilityResponse getReorderTiming() {
        if (!marketCalendarPort.isMarketOpen(java.time.LocalDate.now(TimeZones.KST))) {
            return new ReorderTimingAvailabilityResponse(false, false, false);
        }
        return ReorderTimingAvailabilityResponse.from(DstInfo.calculate().reorderTimingAvailability());
    }

    @PostMapping("/trades/reorders")
    public AdminReorderResponse reorder(
            @AuthenticationPrincipal UUID adminId,
            @RequestBody @Valid AdminReorderRequest request) {
        return AdminReorderResponse.from(adminReorder.reorder(adminId, request.toCommand()));
    }

    // accountId → Account 전체 매핑 (N+1 방지용 일괄 조회)
    private Map<UUID, Account> buildAccountMap() {
        return adminQuery.listAccounts(null, null).stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
    }

    // 주문 목록 → AdminTradeResponse 목록 변환 (accountMap/userMap/strategyTypeMap 공통 조립)
    private List<AdminTradeResponse> toResponses(List<Order> orders) {
        Map<UUID, Account> accountMap = buildAccountMap();
        Map<UUID, AdminUserView> userMap = AdminUserViews.mapById(adminUser);
        Set<UUID> cycleIds = orders.stream()
                .map(Order::strategyCycleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, AdminCycleStrategySummary> strategySummaryMap = adminQuery.getStrategySummariesByCycleIds(cycleIds);
        return orders.stream()
                .map(o -> AdminTradeResponse.from(o, accountMap, userMap, strategySummaryMap))
                .toList();
    }
}
