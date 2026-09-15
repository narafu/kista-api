package com.kista.admin.adapter.in.web;

import com.kista.admin.adapter.in.web.dto.AdminManualTradeCorrectionRequest;
import com.kista.admin.adapter.in.web.dto.AdminReorderRequest;
import com.kista.admin.adapter.in.web.dto.AdminReorderResponse;
import com.kista.admin.adapter.in.web.dto.AdminTradeCorrectionResponse;
import com.kista.admin.adapter.in.web.dto.AdminTradeResponse;
import com.kista.admin.adapter.in.web.dto.ReorderTimingAvailabilityResponse;
import com.kista.admin.domain.model.AdminAccountView;
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminStrategySummary;
import com.kista.user.domain.model.AdminUserView;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.application.usecase.AdminQueryUseCase;
import com.kista.admin.application.usecase.AdminReorderUseCase;
import com.kista.admin.application.usecase.AdminTradeCorrectionUseCase;
import com.kista.admin.application.usecase.AdminUserUseCase;
import io.swagger.v3.oas.annotations.Operation;
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
    private final TradingCommandPort tradingCommandPort;            // 재주문 시점 가용성 조회(개장 여부 판정 포함)

    // 전체 거래 내역 목록 — 일괄 조회로 N+1 방지
    @Operation(summary = "전체 거래 내역 조회", description = "일괄 조회로 N+1을 방지합니다. from/to로 기간 필터링 가능합니다.")
    @GetMapping("/trades")
    public List<AdminTradeResponse> listTrades(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return toResponses(adminQuery.listTrades(from, to));
    }

    @Operation(summary = "전략별 거래일 목록 조회")
    @GetMapping("/accounts/{accountId}/strategies/{strategyId}/trade-dates")
    public List<LocalDate> listStrategyTradeDates(
            @PathVariable UUID accountId,
            @PathVariable UUID strategyId) {
        return adminQuery.listStrategyTradeDates(accountId, strategyId);
    }

    @Operation(summary = "전략별 주문 목록 조회", description = "지정한 거래일(tradeDate)의 주문 목록을 반환합니다.")
    @GetMapping("/accounts/{accountId}/strategies/{strategyId}/orders")
    public List<AdminTradeResponse> listStrategyOrders(
            @PathVariable UUID accountId,
            @PathVariable UUID strategyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        List<AdminOrderView> orders = adminQuery.listStrategyOrders(accountId, strategyId, tradeDate).stream()
                .filter(order -> accountId.equals(order.accountId()))
                .toList();
        if (orders.isEmpty()) return List.of();
        // 단일 계좌만 조회 — 전체 풀스캔 불필요
        AdminAccountView account = adminQuery.findAccount(accountId)
                .orElseThrow(() -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));
        AdminUserView user = adminUser.findUser(account.userId())
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + account.userId()));
        Map<UUID, AdminAccountView> accountMap = Map.of(accountId, account);
        Map<UUID, AdminUserView> userMap = Map.of(account.userId(), user);
        Set<UUID> cycleIds = orders.stream().map(AdminOrderView::strategyCycleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, AdminStrategySummary> strategySummaryMap = adminQuery.getStrategySummariesByCycleIds(cycleIds);
        return orders.stream().map(o -> AdminTradeResponse.from(o, accountMap, userMap, strategySummaryMap)).toList();
    }

    // 관리자 수동 체결 보정 — fills 배열 순서대로 여러 건을 원자적으로 반영
    @Operation(summary = "수동 체결 보정", description = "fills 배열 순서대로 여러 건을 원자적으로 반영합니다.")
    @PostMapping("/trades/manual-fills")
    public AdminTradeCorrectionResponse correctManualFills(
            @AuthenticationPrincipal UUID adminId,
            @RequestBody @Valid AdminManualTradeCorrectionRequest request) {
        return AdminTradeCorrectionResponse.from(
                adminTradeCorrection.correctManualFills(adminId, request.toCommand()));
    }

    // 재주문 시점 가용성 조회 — UI 주문시점 셀렉터 disable 판단용
    @Operation(summary = "재주문 시점 가용성 조회", description = "UI 주문시점 셀렉터의 disable 여부 판단에 사용합니다.")
    @GetMapping("/trades/reorder-timing")
    public ReorderTimingAvailabilityResponse getReorderTiming() {
        // 개장 여부 판정은 trading-core 쪽 내부 API 구현부(MarketCalendarPort.isMarketOpen)로 이관됨
        return ReorderTimingAvailabilityResponse.from(tradingCommandPort.reorderTimingAvailability());
    }

    @Operation(summary = "관리자 재주문")
    @PostMapping("/trades/reorders")
    public AdminReorderResponse reorder(
            @AuthenticationPrincipal UUID adminId,
            @RequestBody @Valid AdminReorderRequest request) {
        return AdminReorderResponse.from(adminReorder.reorder(adminId, request.toCommand()));
    }

    // accountId → AdminAccountView 전체 매핑 (N+1 방지용 일괄 조회)
    private Map<UUID, AdminAccountView> buildAccountMap() {
        return adminQuery.listAccounts(null, null).stream()
                .collect(Collectors.toMap(AdminAccountView::id, Function.identity()));
    }

    // 주문 목록 → AdminTradeResponse 목록 변환 (accountMap/userMap/strategyTypeMap 공통 조립)
    private List<AdminTradeResponse> toResponses(List<AdminOrderView> orders) {
        Map<UUID, AdminAccountView> accountMap = buildAccountMap();
        Map<UUID, AdminUserView> userMap = AdminUserViews.mapById(adminUser);
        Set<UUID> cycleIds = orders.stream()
                .map(AdminOrderView::strategyCycleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, AdminStrategySummary> strategySummaryMap = adminQuery.getStrategySummariesByCycleIds(cycleIds);
        return orders.stream()
                .map(o -> AdminTradeResponse.from(o, accountMap, userMap, strategySummaryMap))
                .toList();
    }
}
