package com.kista.trading.adapter.in.web;

import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategySummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

// admin의 TradingQueryHttpAdapter가 소비하는 내부 전용 읽기 엔드포인트 — X-Internal-Token 인증
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading")
@RequiredArgsConstructor
public class TradingInternalQueryController {

    private final OrderPort orderPort;
    private final StrategyPort strategyPort;

    @Operation(summary = "기간 내 전체 주문 조회", description = "관리자 거래내역 조회용. X-Internal-Token 필수.")
    @GetMapping("/orders")
    public List<Order> listOrders(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return orderPort.findAll(from, to);
    }

    @Operation(summary = "기간 내 distinct 계좌 ID", description = "이상징후 감지용.")
    @GetMapping("/orders/distinct-account-ids")
    public List<UUID> listDistinctAccountIds(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return orderPort.findDistinctAccountIdsByTradeDateBetween(from, to);
    }

    @Operation(summary = "계좌 단건 전략 목록")
    @GetMapping("/accounts/{accountId}/strategies")
    public List<Strategy> listStrategiesByAccount(@PathVariable UUID accountId) {
        return strategyPort.findByAccountId(accountId);
    }

    @Operation(summary = "계좌 다건 전략 배치 조회", description = "N+1 방지 배치 조회.")
    @PostMapping("/strategies/by-account-ids")
    public Map<UUID, List<Strategy>> listStrategiesByAccountIds(@RequestBody Set<UUID> accountIds) {
        return strategyPort.findByAccountIds(accountIds);
    }

    @Operation(summary = "사이클 ID 기준 전략 요약 배치 조회")
    @PostMapping("/strategy-summaries")
    public Map<UUID, StrategySummary> getStrategySummariesByCycleIds(@RequestBody Set<UUID> cycleIds) {
        return strategyPort.findSummariesByCycleIds(cycleIds);
    }

    @Operation(summary = "전략 단건 주문 조회")
    @GetMapping("/accounts/{accountId}/strategies/{strategyId}/orders")
    public List<Order> listStrategyOrders(
            @PathVariable UUID accountId, @PathVariable UUID strategyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        requireStrategyOwnedByAccount(accountId, strategyId);
        return orderPort.findByStrategyId(strategyId, tradeDate, tradeDate);
    }

    @Operation(summary = "전략 단건 거래일 목록")
    @GetMapping("/accounts/{accountId}/strategies/{strategyId}/trade-dates")
    public List<LocalDate> listStrategyTradeDates(@PathVariable UUID accountId, @PathVariable UUID strategyId) {
        requireStrategyOwnedByAccount(accountId, strategyId);
        return orderPort.findTradeDatesByStrategyId(strategyId);
    }

    // 경로 계층 정합성 검증 — 기존 AdminQueryService.requireStrategyOwnedByAccount와 동일 규칙,
    // 소유권 검증이 데이터를 가진 trading 쪽으로 이전됐다. 여기서 던진 404는 admin의
    // TradingQueryHttpAdapter가 다시 NoSuchElementException으로 되돌린다.
    private void requireStrategyOwnedByAccount(UUID accountId, UUID strategyId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        if (!strategy.accountId().equals(accountId)) {
            throw new NoSuchElementException("전략이 해당 계좌에 속하지 않습니다");
        }
    }
}
