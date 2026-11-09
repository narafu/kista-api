package com.kista.api.benchmark.application.port.output;

import com.kista.api.benchmark.domain.model.BenchmarkScope;
import com.kista.api.benchmark.domain.model.InvestmentPoint;
import com.kista.api.benchmark.domain.model.StrategyRef;

import com.kista.shared.kernel.Currency;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvestmentPointsPort {
    record Result(List<InvestmentPoint> points, LocalDate effectiveFrom, LocalDate effectiveTo, StrategyRef selectedStrategy,
                  Currency currency) {} // 투자 통화 — STRATEGY=전략 계좌 통화, PORTFOLIO=요청 통화

    Result fetch(UUID userId, BenchmarkScope scope, UUID strategyId, Currency currency, LocalDate from, LocalDate to);
}
