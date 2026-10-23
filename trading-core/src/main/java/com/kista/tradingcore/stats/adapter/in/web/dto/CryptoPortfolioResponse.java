package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.List;

// 코인 계좌 포트폴리오 응답 — 주식 /portfolio(USD·정수 holdings)와 스키마를 분리해 openapi 단일 타입 유지
public record CryptoPortfolioResponse(
        @Schema(description = "원화 예수금 (가용 + 주문 대기)") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal krwCash,
        @Schema(description = "코인별 보유") List<CryptoHoldingResponse> holdings,
        @Schema(description = "총자산 (KRW) = 예수금 + 평가액 합") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal totalAssetKrw
) {
    public static CryptoPortfolioResponse from(CryptoPortfolio p) {
        return new CryptoPortfolioResponse(p.cash(),
                p.holdings().stream().map(CryptoHoldingResponse::from).toList(), p.totalAsset());
    }

    public record CryptoHoldingResponse(
            @Schema(description = "종목", example = "BTC") String ticker,
            @Schema(description = "보유 수량 (소수 8자리)") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal quantity,
            @Schema(description = "평균 매수가 (KRW)") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal avgBuyPrice,
            @Schema(description = "현재가 (KRW, 시세 없으면 null)", nullable = true) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal currentPrice,
            @Schema(description = "평가액 (KRW, 원 단위 HALF_UP, 시세 없으면 null)", nullable = true) @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal evalAmountKrw
    ) {
        static CryptoHoldingResponse from(CryptoPortfolio.Holding h) {
            return new CryptoHoldingResponse(h.ticker().name(), h.quantity(), h.avgBuyPrice(), h.currentPrice(), h.evalAmount());
        }
    }
}
