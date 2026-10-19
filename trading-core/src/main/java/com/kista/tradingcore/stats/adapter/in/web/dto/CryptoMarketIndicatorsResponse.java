package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators;
import com.kista.tradingcore.stats.domain.model.DatedPremiumPair;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 코인 시장 지표 응답 — 도메인 구조 미러링, 섹션 실패 시 null (부분 응답)
public record CryptoMarketIndicatorsResponse(
        @Schema(description = "조립 시각") Instant asOf,
        @Schema(description = "테더 프리미엄 (실패 시 null)", nullable = true) PremiumResponse usdtPremium,
        @Schema(description = "종목별 지표") List<TickerResponse> tickers
) {
    public static CryptoMarketIndicatorsResponse from(CryptoMarketIndicators m) {
        return new CryptoMarketIndicatorsResponse(m.asOf(),
                m.usdtPremium() == null ? null : new PremiumResponse(m.usdtPremium().current(), points(m.usdtPremium().history())),
                m.tickers().stream().map(TickerResponse::from).toList());
    }

    private static List<DatedValueResponse> points(List<DatedValue> history) {
        return history.stream().map(v -> new DatedValueResponse(v.date(), v.value())).toList();
    }

    public record PremiumResponse(
            @Schema(description = "현재 테더 프리미엄 (%)") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal current,
            @Schema(description = "일자별 추이") List<DatedValueResponse> history
    ) {}

    public record DatedValueResponse(
            @Schema(description = "기준 일자") LocalDate date,
            @Schema(description = "값") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal value
    ) {}

    public record TickerResponse(
            @Schema(description = "종목", example = "BTC") String ticker,
            @Schema(description = "김치 프리미엄 (실패 시 null)", nullable = true) KimchiPremiumResponse kimchiPremium,
            @Schema(description = "펀딩비 (실패 시 null)", nullable = true) FundingRateResponse fundingRate,
            @Schema(description = "미결제약정 (실패 시 null)", nullable = true) OpenInterestResponse openInterest
    ) {
        static TickerResponse from(CryptoMarketIndicators.TickerIndicators t) {
            var kp = t.kimchiPremium();
            var fr = t.fundingRate();
            var oi = t.openInterest();
            return new TickerResponse(t.ticker().name(),
                    kp == null ? null : new KimchiPremiumResponse(
                            new PremiumPairResponse(kp.current().official(), kp.current().tether()),
                            kp.history().stream().map(DatedPremiumPairResponse::from).toList()),
                    fr == null ? null : new FundingRateResponse(fr.current(), fr.nextFundingTime(), points(fr.history())),
                    oi == null ? null : new OpenInterestResponse(
                            oi.history().stream().map(v -> new OpenInterestPointResponse(v.date(), v.value())).toList()));
        }
    }

    public record PremiumPairResponse(
            @Schema(description = "공식 환율 기준 김프 (%, 환율 장애 시 null)", nullable = true) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal official,
            @Schema(description = "테더 기준 김프 (%)") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal tether
    ) {}

    public record DatedPremiumPairResponse(
            @Schema(description = "기준 일자") LocalDate date,
            @Schema(description = "공식 환율 기준 김프 (%, 환율 없는 날 null)", nullable = true) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal official,
            @Schema(description = "테더 기준 김프 (%)") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal tether
    ) {
        static DatedPremiumPairResponse from(DatedPremiumPair p) {
            return new DatedPremiumPairResponse(p.date(), p.official(), p.tether());
        }
    }

    public record KimchiPremiumResponse(
            @Schema(description = "현재 김프") PremiumPairResponse current,
            @Schema(description = "일자별 추이") List<DatedPremiumPairResponse> history
    ) {}

    public record FundingRateResponse(
            @Schema(description = "현재 펀딩비 (비율, 예: 0.0001)") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal current,
            @Schema(description = "다음 펀딩 시각") Instant nextFundingTime,
            @Schema(description = "일평균 추이") List<DatedValueResponse> history
    ) {}

    public record OpenInterestPointResponse(
            @Schema(description = "기준 일자") LocalDate date,
            @Schema(description = "미결제약정 명목가 (USD)") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal valueUsd
    ) {}

    public record OpenInterestResponse(
            @Schema(description = "일자별 추이") List<OpenInterestPointResponse> history
    ) {}
}
