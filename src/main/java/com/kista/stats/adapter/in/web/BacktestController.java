package com.kista.stats.adapter.in.web;

import com.kista.stats.adapter.in.web.dto.BacktestResponse;
import com.kista.stats.domain.model.backtest.BacktestCommand;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.stats.application.usecase.BacktestUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@Tag(name = "백테스트", description = "과거 일봉 기반 전략 시뮬레이션")
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestUseCase backtestUseCase;

    @Operation(summary = "전략 백테스트", description = "과거 일봉으로 전략을 시뮬레이션해 자산 곡선·성과 요약·해석 주의사항을 반환. "
            + "initialHoldings/initialAvgPrice로 기존 보유 포지션부터 시작하는 백테스트도 가능(seed=0 허용, 단 예수금과 보유 중 하나는 있어야 함).")
    @GetMapping
    public BacktestResponse run(
            @AuthenticationPrincipal UUID userId, // 로그인 확인 전용 — 백테스트는 계좌와 무관해 소유권 검증 없음
            @RequestParam StrategyType type,
            @RequestParam StrategyTicker ticker,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") BigDecimal seed,
            @RequestParam(required = false) Integer divisionCount,
            @RequestParam(required = false) BigDecimal vrBandWidth,
            @RequestParam(required = false) Integer vrIntervalWeeks,
            @RequestParam(defaultValue = "0") int vrRecurringAmount,
            @RequestParam(required = false) BigDecimal vrInitialValue,
            @RequestParam(required = false) Integer initialHoldings,
            @RequestParam(required = false) BigDecimal initialAvgPrice) {
        BacktestCommand command = new BacktestCommand(type, ticker, from, to, seed,
                divisionCount, vrBandWidth, vrIntervalWeeks, vrRecurringAmount, vrInitialValue,
                initialHoldings, initialAvgPrice);
        return BacktestResponse.from(backtestUseCase.run(command));
    }
}
