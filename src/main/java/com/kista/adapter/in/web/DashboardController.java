package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FidaOrderRequestDto;
import com.kista.adapter.in.web.dto.PortfolioSnapshotResponse;
import com.kista.adapter.in.web.dto.TradeHistoryResponse;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "대시보드", description = "거래 내역, 포트폴리오 스냅샷 조회 및 FIDA 주문")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final GetTradeHistoryUseCase getTradeHistoryUseCase;
    private final GetPortfolioUseCase getPortfolioUseCase;
    private final ExecuteFidaOrderUseCase executeFidaOrderUseCase;

    @Operation(summary = "거래 내역 조회", description = "날짜 범위와 종목으로 필터링. 기본: 최근 30일, 종목 SOXL.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/trades")
    public List<TradeHistoryResponse> getTrades(
            @Parameter(description = "조회 시작일 (기본: 오늘 - 30일)", example = "2025-01-01")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (기본: 오늘)", example = "2025-01-31")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "종목 코드", example = "SOXL")
            @RequestParam(defaultValue = "SOXL") String symbol) {
        LocalDate resolvedFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        return getTradeHistoryUseCase.getHistory(resolvedFrom, resolvedTo, symbol)
                .stream().map(TradeHistoryResponse::from).toList();
    }

    @Operation(summary = "현재 포트폴리오 조회", description = "가장 최근 포트폴리오 스냅샷 1건 반환.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/portfolio/current")
    public PortfolioSnapshotResponse getPortfolioCurrent() {
        return PortfolioSnapshotResponse.from(getPortfolioUseCase.getCurrent());
    }

    @Operation(summary = "포트폴리오 스냅샷 목록", description = "최근 N일간의 포트폴리오 스냅샷 목록 반환.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/portfolio/snapshots")
    public List<PortfolioSnapshotResponse> getPortfolioSnapshots(
            @Parameter(description = "조회 기간 (일 수, 기본: 30)", example = "30")
            @RequestParam(defaultValue = "30") int days) {
        return getPortfolioUseCase.getSnapshots(days)
                .stream().map(PortfolioSnapshotResponse::from).toList();
    }

    @Operation(summary = "FIDA 주문 실행", description = "FIDA 계좌로 즉시 지정가 매매 주문 접수.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "주문 접수 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (파라미터 누락 또는 유효성 오류)")
    })
    @PostMapping("/orders/fida")
    @ResponseStatus(HttpStatus.CREATED)
    public void placeFidaOrder(@RequestBody @Valid FidaOrderRequestDto dto) {
        executeFidaOrderUseCase.execute(new FidaOrderRequest(
                dto.symbol(), dto.direction(), dto.qty(), dto.price()));
    }
}
