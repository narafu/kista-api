package com.kista.benchmark.adapter.out.internal;

import com.kista.contract.stats.InvestmentPointsResponse;
import com.kista.platform.internalapi.InternalApiErrorDetails;
import com.kista.platform.internalapi.InternalApiStatusHandlers;
import com.kista.benchmark.application.port.output.InvestmentPointsPort;
import com.kista.sharedkernel.BenchmarkGranularity;
import com.kista.benchmark.domain.model.BenchmarkScope;
import com.kista.benchmark.domain.model.InvestmentPoint;
import com.kista.benchmark.domain.model.StrategyRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class InvestmentPointsHttpAdapter implements InvestmentPointsPort {

    private final RestClient internalApiRestClient;

    @Override
    public Result fetch(UUID userId, BenchmarkScope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity) {
        RestClient.ResponseSpec spec = internalApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/internal/trading/stats/investment-points")
                        .queryParam("userId", userId)
                        .queryParam("scope", scope)
                        .queryParamIfPresent("strategyId", Optional.ofNullable(strategyId))
                        .queryParamIfPresent("from", Optional.ofNullable(from))
                        .queryParamIfPresent("to", Optional.ofNullable(to))
                        .queryParam("granularity", granularity)
                        .build())
                .retrieve()
                // trading 쪽 GlobalExceptionHandler가 매핑한 상태코드를 원래 예외 타입으로 되돌린다 —
                // 없으면 SecurityException/NoSuchElementException/IllegalArgumentException이 api
                // 쪽에서 매핑되지 않는 HttpClientErrorException으로 흘러 500(catch-all)로 뭉개진다.
                // ProblemDetail.detail의 원 메시지를 그대로 옮겨 담는다(고정 문구로 뭉개지 않음).
                // 403은 이 어댑터 전용 표지 예외라 공용 팩토리 대상이 아니라 그대로 유지한다
                .onStatus(status -> status.value() == 403, (request, response) -> {
                    throw new SecurityException(InternalApiErrorDetails.detailOrDefault(response, "소유하지 않은 리소스입니다"));
                });
        InvestmentPointsResponse response = InternalApiStatusHandlers.badRequestAsIllegalArgument(
                        InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "리소스를 찾을 수 없습니다"),
                        "잘못된 요청입니다")
                .body(InvestmentPointsResponse.class);
        return toResult(response);
    }

    // contract → stats 도메인 매핑 — 벤치마크 계산(HousingBenchmarkComparisonBuilder 등)은 root stats 도메인 타입을 쓴다
    private static Result toResult(InvestmentPointsResponse r) {
        List<InvestmentPoint> points = r.points().stream()
                .map(p -> new InvestmentPoint(p.baseDate(), p.investmentIndexUsd(), p.periodReturn()))
                .toList();
        StrategyRef strategy = r.selectedStrategy() == null ? null
                : new StrategyRef(r.selectedStrategy().id(), r.selectedStrategy().type(), r.selectedStrategy().ticker());
        return new Result(points, r.effectiveFrom(), r.effectiveTo(), strategy);
    }
}
