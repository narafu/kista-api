package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.tradingcore.stats.domain.model.CryptoMarketIndicators;
import com.kista.tradingcore.stats.domain.model.DatedLongShortRatio;
import com.kista.tradingcore.stats.domain.model.DatedPremiumPair;
import com.kista.tradingcore.stats.domain.model.DatedValuation;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 코인 시장 지표 응답 — 도메인 구조 미러링, 섹션 실패 시 null (부분 응답).
// 중첩 스키마명은 CryptoMarketIndicators 접두사로 고유화 — 일반명(TickerResponse 등)이 다른 DTO와 겹치면 springdoc이 개명해
// CryptoMarketIndicatorsOpenApiCustomizer가 대상을 놓친다
public record CryptoMarketIndicatorsResponse(
        @Schema(description = "조립 시각", requiredMode = REQUIRED) Instant asOf,
        @Schema(description = "테더 프리미엄 (실패 시 null)", nullable = true, requiredMode = REQUIRED) PremiumResponse usdtPremium,
        @Schema(description = "종목별 지표", requiredMode = REQUIRED) List<TickerResponse> tickers,
        @Schema(description = "BTC 반감기 카운트다운 (실패 시 null)", nullable = true, requiredMode = REQUIRED) HalvingResponse halving
) {
    public static CryptoMarketIndicatorsResponse from(CryptoMarketIndicators m) {
        return new CryptoMarketIndicatorsResponse(m.asOf(),
                m.usdtPremium() == null ? null : new PremiumResponse(m.usdtPremium().current(), points(m.usdtPremium().history())),
                m.tickers().stream().map(TickerResponse::from).toList(),
                m.halving() == null ? null : new HalvingResponse(m.halving().currentHeight(), m.halving().nextHalvingHeight(),
                        m.halving().blocksRemaining(), m.halving().estimatedAt()));
    }

    private static List<DatedValueResponse> points(List<DatedValue> history) {
        return history.stream().map(v -> new DatedValueResponse(v.date(), v.value())).toList();
    }

    @Schema(name = "CryptoMarketIndicatorsPremium")
    public record PremiumResponse(
            @Schema(description = "현재 테더 프리미엄 (%)", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal current,
            @Schema(description = "일자별 추이", requiredMode = REQUIRED) List<DatedValueResponse> history
    ) {}

    @Schema(name = "CryptoMarketIndicatorsCoinbasePremium")
    public record CoinbasePremiumResponse(
            @Schema(description = "현재 코인베이스 프리미엄 (%) — (코인베이스 <코인>-USD ÷ (바이낸스 <코인>USDT × 코인베이스 USDT-USD) − 1) × 100", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal current,
            @Schema(description = "일자별 추이 (UTC 일봉 종가, 진행 중 봉 제외)", requiredMode = REQUIRED) List<DatedValueResponse> history
    ) {}

    @Schema(name = "CryptoMarketIndicatorsHalving")
    public record HalvingResponse(
            @Schema(description = "현재 BTC 블록 높이", requiredMode = REQUIRED) long currentHeight,
            @Schema(description = "다음 반감기 블록 높이 (210,000 배수)", requiredMode = REQUIRED) long nextHalvingHeight,
            @Schema(description = "남은 블록 수", requiredMode = REQUIRED) long blocksRemaining,
            @Schema(description = "예상 반감기 시각 (조립 시각 + 남은 블록 × 10분, 추정치)", requiredMode = REQUIRED) Instant estimatedAt
    ) {}

    @Schema(name = "CryptoMarketIndicatorsDatedValue")
    public record DatedValueResponse(
            @Schema(description = "기준 일자", requiredMode = REQUIRED) LocalDate date,
            @Schema(description = "값", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal value
    ) {}

    @Schema(name = "CryptoMarketIndicatorsTicker")
    public record TickerResponse(
            @Schema(description = "종목", example = "BTC", requiredMode = REQUIRED) String ticker,
            @Schema(description = "김치 프리미엄 (실패 시 null)", nullable = true, requiredMode = REQUIRED) KimchiPremiumResponse kimchiPremium,
            @Schema(description = "펀딩비 (실패 시 null)", nullable = true, requiredMode = REQUIRED) FundingRateResponse fundingRate,
            @Schema(description = "미결제약정 (실패 시 null)", nullable = true, requiredMode = REQUIRED) OpenInterestResponse openInterest,
            @Schema(description = "장기 밸류에이션 — 직전 완성 일봉 종가 기준 (실패 시 null)", nullable = true, requiredMode = REQUIRED) ValuationResponse valuation,
            @Schema(description = "롱/숏 계좌 비율 — 계좌 수(사람) 기준, 포지션 규모(돈) 기준인 펀딩비와 다름 (실패 시 null)", nullable = true, requiredMode = REQUIRED) LongShortRatioResponse longShortRatio,
            @Schema(description = "테더 보정 코인베이스 프리미엄 — 미국 현물 수요 (실패 시 null)", nullable = true, requiredMode = REQUIRED) CoinbasePremiumResponse coinbasePremium
    ) {
        static TickerResponse from(CryptoMarketIndicators.TickerIndicators t) {
            var kp = t.kimchiPremium();
            var fr = t.fundingRate();
            var oi = t.openInterest();
            var va = t.valuation();
            var ls = t.longShortRatio();
            var cb = t.coinbasePremium();
            return new TickerResponse(t.ticker().name(),
                    kp == null ? null : new KimchiPremiumResponse(
                            new PremiumPairResponse(kp.current().official(), kp.current().tether()),
                            kp.history().stream().map(DatedPremiumPairResponse::from).toList()),
                    fr == null ? null : new FundingRateResponse(fr.current(), fr.nextFundingTime(), points(fr.history())),
                    oi == null ? null : new OpenInterestResponse(
                            oi.history().stream().map(v -> new OpenInterestPointResponse(v.date(), v.value())).toList()),
                    va == null ? null : new ValuationResponse(DatedValuationResponse.from(va.current()),
                            va.history().stream().map(DatedValuationResponse::from).toList()),
                    ls == null ? null : new LongShortRatioResponse(ls.longAccount(), ls.longShortRatio(),
                            ls.history().stream().map(DatedLongShortRatioResponse::from).toList()),
                    cb == null ? null : new CoinbasePremiumResponse(cb.current(), points(cb.history())));
        }
    }

    @Schema(name = "CryptoMarketIndicatorsPremiumPair")
    public record PremiumPairResponse(
            @Schema(description = "공식 환율 기준 김프 (%, 환율 장애 시 null)", nullable = true, requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal official,
            @Schema(description = "테더 기준 김프 (%)", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal tether
    ) {}

    @Schema(name = "CryptoMarketIndicatorsDatedPremiumPair")
    public record DatedPremiumPairResponse(
            @Schema(description = "기준 일자", requiredMode = REQUIRED) LocalDate date,
            @Schema(description = "공식 환율 기준 김프 (%, 환율 없는 날 null)", nullable = true, requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal official,
            @Schema(description = "테더 기준 김프 (%)", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal tether
    ) {
        static DatedPremiumPairResponse from(DatedPremiumPair p) {
            return new DatedPremiumPairResponse(p.date(), p.official(), p.tether());
        }
    }

    @Schema(name = "CryptoMarketIndicatorsKimchiPremium")
    public record KimchiPremiumResponse(
            @Schema(description = "현재 김프", requiredMode = REQUIRED) PremiumPairResponse current,
            @Schema(description = "일자별 추이", requiredMode = REQUIRED) List<DatedPremiumPairResponse> history
    ) {}

    @Schema(name = "CryptoMarketIndicatorsFundingRate")
    public record FundingRateResponse(
            @Schema(description = "현재 펀딩비 (비율, 예: 0.0001)", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal current,
            @Schema(description = "다음 펀딩 시각", requiredMode = REQUIRED) Instant nextFundingTime,
            @Schema(description = "일평균 추이", requiredMode = REQUIRED) List<DatedValueResponse> history
    ) {}

    @Schema(name = "CryptoMarketIndicatorsOpenInterestPoint")
    public record OpenInterestPointResponse(
            @Schema(description = "기준 일자", requiredMode = REQUIRED) LocalDate date,
            @Schema(description = "미결제약정 명목가 (USD)", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal valueUsd
    ) {}

    @Schema(name = "CryptoMarketIndicatorsOpenInterest")
    public record OpenInterestResponse(
            @Schema(description = "일자별 추이", requiredMode = REQUIRED) List<OpenInterestPointResponse> history
    ) {}

    @Schema(name = "CryptoMarketIndicatorsDatedValuation")
    public record DatedValuationResponse(
            @Schema(description = "기준 일자 (UTC 일봉)", requiredMode = REQUIRED) LocalDate date,
            @Schema(description = "그날 종가 (USDT)", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal close,
            @Schema(description = "200주 이동평균 (USDT, 1400일 단순평균 — 이력 1400일 미만이면 null)", nullable = true, requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal wma200,
            @Schema(description = "종가 / 200주 이동평균 배수 (200주 이동평균이 null이면 null)", nullable = true, requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal wma200Multiple,
            @Schema(description = "Mayer Multiple — 종가 / 200일 이동평균 (이력 200일 미만이면 null)", nullable = true, requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal mayerMultiple,
            @Schema(description = "최고 종가(ATH) 대비 하락률 (%, 0 이하)", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal athDrawdownPercent
    ) {
        static DatedValuationResponse from(DatedValuation v) {
            return new DatedValuationResponse(v.date(), v.close(), v.wma200(), v.wma200Multiple(), v.mayerMultiple(), v.athDrawdownPercent());
        }
    }

    @Schema(name = "CryptoMarketIndicatorsValuation")
    public record ValuationResponse(
            @Schema(description = "현재 값 — 실시간이 아니라 직전 완성 일봉 기준 (history 마지막 점과 같음)", requiredMode = REQUIRED) DatedValuationResponse current,
            @Schema(description = "일자별 추이 (각 일자는 그날까지의 일봉으로만 계산)", requiredMode = REQUIRED) List<DatedValuationResponse> history
    ) {}

    @Schema(name = "CryptoMarketIndicatorsDatedLongShortRatio")
    public record DatedLongShortRatioResponse(
            @Schema(description = "기준 일자", requiredMode = REQUIRED) LocalDate date,
            @Schema(description = "롱 포지션 계좌 비율 (0~1)", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal longAccount,
            @Schema(description = "롱 계좌 수 / 숏 계좌 수", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal longShortRatio
    ) {
        static DatedLongShortRatioResponse from(DatedLongShortRatio r) {
            return new DatedLongShortRatioResponse(r.date(), r.longAccount(), r.longShortRatio());
        }
    }

    @Schema(name = "CryptoMarketIndicatorsLongShortRatio")
    public record LongShortRatioResponse(
            @Schema(description = "최신 롱 포지션 계좌 비율 (0~1, 계좌 수 기준)", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal longAccount,
            @Schema(description = "최신 롱 계좌 수 / 숏 계좌 수", requiredMode = REQUIRED) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal longShortRatio,
            @Schema(description = "일자별 추이 (최근 30일 고정)", requiredMode = REQUIRED) List<DatedLongShortRatioResponse> history
    ) {}
}
