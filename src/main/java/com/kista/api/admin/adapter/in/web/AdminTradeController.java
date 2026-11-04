package com.kista.api.admin.adapter.in.web;

import com.kista.api.admin.adapter.in.web.dto.AdminManualTradeCorrectionRequest;
import com.kista.api.admin.adapter.in.web.dto.AdminTradeCorrectionResponse;
import com.kista.api.admin.adapter.in.web.dto.AdminTradeResponse;
import com.kista.shared.contract.account.AccountSummaryResponse;
import com.kista.shared.contract.trading.OrderResponse;
import com.kista.shared.contract.trading.StrategySummaryResponse;
import com.kista.api.user.domain.model.UserSummary;
import com.kista.api.admin.application.usecase.AdminQueryUseCase;
import com.kista.api.admin.application.usecase.AdminTradeCorrectionUseCase;
import com.kista.api.admin.application.usecase.AdminUserUseCase;
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

    // 관리자 수동 체결 보정 — fills 배열 순서대로 여러 건을 원자적으로 반영
    @Operation(summary = "수동 체결 보정", description = "fills 배열 순서대로 여러 건을 원자적으로 반영합니다.")
    @PostMapping("/trades/manual-fills")
    public AdminTradeCorrectionResponse correctManualFills(
            @AuthenticationPrincipal UUID adminId,
            @RequestBody @Valid AdminManualTradeCorrectionRequest request) {
        return AdminTradeCorrectionResponse.from(
                adminTradeCorrection.correctManualFills(adminId, request.toCommand()));
    }

    // accountId → AccountSummaryResponse 전체 매핑 (N+1 방지용 일괄 조회)
    private Map<UUID, AccountSummaryResponse> buildAccountMap() {
        return adminQuery.listAccounts(null, null).stream()
                .collect(Collectors.toMap(AccountSummaryResponse::id, Function.identity()));
    }

    // 주문 목록 → AdminTradeResponse 목록 변환 (accountMap/userMap/strategyTypeMap 공통 조립)
    private List<AdminTradeResponse> toResponses(List<OrderResponse> orders) {
        Map<UUID, AccountSummaryResponse> accountMap = buildAccountMap();
        Map<UUID, UserSummary> userMap = UserSummaries.mapById(adminUser);
        Set<UUID> cycleIds = orders.stream()
                .map(OrderResponse::strategyCycleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, StrategySummaryResponse> strategySummaryMap = adminQuery.getStrategySummariesByCycleIds(cycleIds);
        return orders.stream()
                .map(o -> AdminTradeResponse.from(o, accountMap, userMap, strategySummaryMap))
                .toList();
    }
}
