package com.kista.trading.stats.adapter.in.web;

import com.kista.broker.application.port.output.ExchangeRatePort;
import com.kista.trading.stats.adapter.in.web.dto.InvestmentPointsResponse;
import com.kista.trading.stats.application.usecase.InvestmentPointsQuery;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
import com.kista.trading.stats.domain.model.InvestmentPointsResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/stats")
@RequiredArgsConstructor
public class TradingStatsInternalController {

    private final InvestmentPointsQuery investmentPointsQuery;
    private final ExchangeRatePort exchangeRatePort;

    @Operation(summary = "투자 성과 시리즈 조회", description = "벤치마크 비교용 InvestmentPoint 시리즈. X-Internal-Token 헤더 필수.")
    @GetMapping("/investment-points")
    public InvestmentPointsResponse getInvestmentPoints(
            @RequestParam UUID userId,
            @RequestParam InvestmentPointsQuery.Scope scope,
            @RequestParam(required = false) UUID strategyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam BenchmarkGranularity granularity) {
        InvestmentPointsResult result = investmentPointsQuery.fetch(userId, scope, strategyId, from, to, granularity);
        return new InvestmentPointsResponse(
                result.points(), result.effectiveFrom(), result.effectiveTo(), result.selectedStrategy());
    }

    @Operation(summary = "현재 USD/KRW 매매기준율 조회", description = "TOSS_INVEST 매매기준율(midRate) 단일 값. X-Internal-Token 헤더 필수.")
    @GetMapping("/exchange-rate")
    public BigDecimal exchangeRate() {
        return exchangeRatePort.getExchangeRate().midRate();
    }
}
