package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.*;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.in.StrategyUseCase;
import com.kista.domain.port.in.TradingExecutionUseCase;
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

    private final StrategyUseCase tradingCycle;                  // CRUD + pause/resume
    private final AccountStatisticsUseCase accountStatistics;   // 사이클 이력 조회
    private final TradingExecutionUseCase tradingExecution;      // 수동 실행 + 주문 취소

    // 로그인 사용자의 전 계좌 전략 목록 — 모바일 전략 탭용
    @Operation(summary = "내 전체 거래 사이클 목록")
    @GetMapping("/api/trading-cycles")
    public List<TradingCycleResponse> listMine(@AuthenticationPrincipal UUID userId) {
        return tradingCycle.listByUserId(userId).stream()
                .map(TradingCycleResponse::from)
                .toList();
    }

    // 계좌의 거래 사이클 목록 조회
    @Operation(summary = "거래 사이클 목록 조회")
    @GetMapping("/api/accounts/{accountId}/trading-cycles")
    public List<TradingCycleResponse> list(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return tradingCycle.listByAccountId(accountId, userId).stream()
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
                tradingCycle.register(userId, accountId, request.toRegisterCommand())
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
                tradingCycle.update(id, userId, request.toUpdateCommand())
        );
    }

    // 거래 사이클 삭제
    @Operation(summary = "거래 사이클 삭제")
    @DeleteMapping("/api/trading-cycles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        tradingCycle.delete(id, userId);
    }

    // 거래 사이클 중지 (ACTIVE → PAUSED)
    @Operation(summary = "거래 사이클 중지")
    @PatchMapping("/api/trading-cycles/{id}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        tradingCycle.pause(id, userId);
    }

    // 거래 사이클 재개 (PAUSED → ACTIVE)
    @Operation(summary = "거래 사이클 재개")
    @PatchMapping("/api/trading-cycles/{id}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        tradingCycle.resume(id, userId);
    }

    // 사이클 수동 실행 — Phase A/B 동기(접수), Phase C 비동기(체결·이력·알림)
    @Operation(summary = "매매 수동 실행")
    @PostMapping("/api/trading-cycles/{id}/execute")
    public ExecuteOrdersResponse executeManually(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return ExecuteOrdersResponse.from(tradingExecution.executeManually(id, userId));
    }

    // 오늘 수동 실행으로 PLACED된 사이클 주문 전체 취소 (best-effort)
    @Operation(summary = "수동 실행 주문 취소 (오늘 PLACED 주문 전체)")
    @DeleteMapping("/api/trading-cycles/{id}/execute")
    public CancelOrdersResponse cancelExecute(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return CancelOrdersResponse.from(tradingExecution.cancelByCycle(id, userId));
    }

    // 전략(사이클) 기준 다음 주문 미리보기 — DB 저장 없음, 휴장·상태 무관 강제 계산
    @Operation(summary = "전략 다음 주문 미리보기")
    @GetMapping("/api/trading-cycles/{id}/preview")
    public NextOrdersResponse preview(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return NextOrdersResponse.from(tradingExecution.preview(id, userId));
    }

    // 전략 등록/수정 폼용 최소시드·기준가 미리보기
    @Operation(summary = "전략 최소시드·기준가 미리보기")
    @GetMapping("/api/accounts/{accountId}/strategy-seed-preview")
    public StrategySeedPreviewResponse seedPreview(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam Strategy.Type type,
            @RequestParam Strategy.Ticker ticker,
            @RequestParam(defaultValue = "20") int divisionCount) {
        return StrategySeedPreviewResponse.from(
                accountStatistics.strategySeedPreview(accountId, userId, type, ticker, divisionCount));
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
                accountStatistics.getByStrategy(
                        strategyId, userId, from, to, cursorInstant, Math.min(size, 200)));
    }
}
