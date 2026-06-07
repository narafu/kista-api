package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.CancelOrdersResponse;
import com.kista.adapter.in.web.dto.CycleHistoryPageResponse;
import com.kista.adapter.in.web.dto.ExecuteOrdersResponse;
import com.kista.adapter.in.web.dto.TradingCycleRequest;
import com.kista.adapter.in.web.dto.TradingCycleResponse;
import com.kista.domain.port.in.CancelOrderUseCase;
import com.kista.domain.port.in.DeleteTradingCycleUseCase;
import com.kista.domain.port.in.GetAccountStatisticsUseCase;
import com.kista.domain.port.in.GetTradingCycleUseCase;
import com.kista.domain.port.in.ManualExecuteTradingUseCase;
import com.kista.domain.port.in.PauseTradingCycleUseCase;
import com.kista.domain.port.in.RegisterTradingCycleUseCase;
import com.kista.domain.port.in.ResumeTradingCycleUseCase;
import com.kista.domain.port.in.UpdateTradingCycleUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "거래 사이클", description = "계좌별 매매 사이클 등록·조회·수정·삭제·중지·재개")
@RestController
@RequiredArgsConstructor
@Slf4j
public class TradingCycleController {

    private final RegisterTradingCycleUseCase registerCycle;
    private final UpdateTradingCycleUseCase updateCycle;
    private final DeleteTradingCycleUseCase deleteCycle;
    private final GetTradingCycleUseCase getCycle;
    private final PauseTradingCycleUseCase pauseCycle;
    private final ResumeTradingCycleUseCase resumeCycle;
    private final GetAccountStatisticsUseCase statisticsUseCase;
    private final ManualExecuteTradingUseCase manualExecute; // 수동 실행
    private final CancelOrderUseCase cancelOrder;            // 주문 취소

    // 계좌의 거래 사이클 목록 조회
    @Operation(summary = "거래 사이클 목록 조회")
    @GetMapping("/api/accounts/{accountId}/trading-cycles")
    public List<TradingCycleResponse> list(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return getCycle.listByAccountId(accountId, userId).stream()
                .map(TradingCycleResponse::from)
                .toList();
    }

    // 거래 사이클 등록
    @Operation(summary = "거래 사이클 등록")
    @PostMapping("/api/accounts/{accountId}/trading-cycles")
    @ResponseStatus(HttpStatus.CREATED)
    public TradingCycleResponse register(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody TradingCycleRequest request) {
        return TradingCycleResponse.from(
                registerCycle.register(userId, accountId, request.toRegisterCommand())
        );
    }

    // 거래 사이클 수정 (cycleSeedType 등 메타 변경)
    @Operation(summary = "거래 사이클 수정")
    @PutMapping("/api/trading-cycles/{id}")
    public TradingCycleResponse update(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @RequestBody TradingCycleRequest request) {
        return TradingCycleResponse.from(
                updateCycle.update(id, userId, request.toUpdateCommand())
        );
    }

    // 거래 사이클 삭제
    @Operation(summary = "거래 사이클 삭제")
    @DeleteMapping("/api/trading-cycles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        deleteCycle.delete(id, userId);
    }

    // 거래 사이클 중지 (ACTIVE → PAUSED)
    @Operation(summary = "거래 사이클 중지")
    @PatchMapping("/api/trading-cycles/{id}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        pauseCycle.pause(id, userId);
    }

    // 거래 사이클 재개 (PAUSED → ACTIVE)
    @Operation(summary = "거래 사이클 재개")
    @PatchMapping("/api/trading-cycles/{id}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        resumeCycle.resume(id, userId);
    }

    // INFINITE 사이클 수동 실행 — Phase A/B 동기(접수), Phase C 비동기(체결·이력·알림)
    @Operation(summary = "매매 수동 실행 (INFINITE 전용)")
    @PostMapping("/api/trading-cycles/{id}/execute")
    public ExecuteOrdersResponse executeManually(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return ExecuteOrdersResponse.from(manualExecute.execute(id, userId));
    }

    // 오늘 수동 실행으로 PLACED된 사이클 주문 전체 취소 (best-effort)
    @Operation(summary = "수동 실행 주문 취소 (오늘 PLACED 주문 전체)")
    @DeleteMapping("/api/trading-cycles/{id}/execute")
    public CancelOrdersResponse cancelExecute(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return CancelOrdersResponse.from(cancelOrder.cancelByCycle(id, userId));
    }

    // 전략(사이클) 기준 거래 이력 조회 — 커서 기반 페이지네이션
    @Operation(summary = "전략 거래 이력 조회")
    @GetMapping("/api/trading-cycles/{strategyId}/history")
    public CycleHistoryPageResponse getStrategyHistory(
            @PathVariable UUID strategyId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        return CycleHistoryPageResponse.from(
                statisticsUseCase.getStrategyCycleHistory(
                        strategyId, userId, from, to, cursorInstant, Math.min(size, 200)));
    }
}
