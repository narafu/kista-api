package com.kista.adapter.in.web.dto;

import com.kista.domain.model.broker.PresentBalanceResult;
import com.kista.domain.model.broker.PresentBalanceResult.Item;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.util.List;

import static java.math.BigDecimal.ZERO;

// KIS CTRP6504R 응답(PresentBalanceResult) → kista-ui 통합 포맷 DTO
// PresentBalanceResult의 camelCase 필드를 kista-ui PortfolioSnapshot 타입과 일치하도록 정규화
public record PortfolioSummaryResponse(
        List<PositionDto> positions,  // output1: 종목별 잔고 목록
        SummaryDto summary            // output3: 계좌 전체 요약
) {

    // 종목별 포지션 — kista-ui PortfolioSnapshot 호환
    public record PositionDto(
            Ticker ticker,             // pdno: 종목코드
            int holdings,              // cblc_qty13: 잔고수량
            BigDecimal avgPrice,       // avg_unpr3: 평균단가 (USD)
            BigDecimal currentPrice,   // ovrs_now_pric1: 현재가 (USD)
            BigDecimal evalAmountUsd,  // frcr_evlu_amt2: 외화평가금액
            BigDecimal profitLossUsd,  // evlu_pfls_amt2: 평가손익 (USD)
            BigDecimal profitRate,     // evlu_pfls_rt1: 평가손익률 %
            String exchangeCode        // ovrs_excg_cd: 해외거래소코드
    ) {}

    // 계좌 전체 요약 — PresentBalanceResult output3 필드
    public record SummaryDto(
            BigDecimal totalAssetUsd,           // tot_asst_amt: 총자산 (KRW — 필드명 quirk 유지)
            BigDecimal totalEvalProfit,         // tot_evlu_pfls_amt: 총평가손익 (KRW)
            BigDecimal totalReturnRate,         // evlu_erng_rt1: 총수익률 %
            BigDecimal totalAssetUsdActual,     // USD 총자산 (포지션+예수금)
            BigDecimal evalProfitUsdSum,        // USD 평가손익 합계
            BigDecimal usdDeposit,              // USD 예수금
            BigDecimal posEvalUsd,              // USD 평가금 (포지션 evalAmountUsd 합계)
            BigDecimal exchangeRateKrwPerUsd    // 환율 (1 USD = ? KRW)
    ) {}

    public static PortfolioSummaryResponse from(PresentBalanceResult balance) {
        List<PositionDto> positions = balance.items().stream()
                .map(item -> new PositionDto(
                        item.ticker(), item.holdings(), item.avgPrice(), item.currentPrice(),
                        item.evalAmountUsd(), item.profitLossUsd(), item.profitRate(), item.exchangeCode()
                ))
                .toList();
        // USD 예수금·포지션 평가금 분리
        BigDecimal usdDeposit = balance.usdDepositActual();
        BigDecimal posEvalUsd = balance.items().stream()
                .map(Item::evalAmountUsd).reduce(ZERO, BigDecimal::add);
        // USD 총자산 = 포지션 USD 합계 + USD 예수금
        BigDecimal totalAssetUsdActual = posEvalUsd.add(usdDeposit);
        // USD 평가손익 = 포지션 USD 손익 합계
        BigDecimal evalProfitUsdSum = balance.items().stream()
                .map(Item::profitLossUsd).reduce(ZERO, BigDecimal::add);
        SummaryDto summary = new SummaryDto(
                balance.totalAssetUsd(), balance.totalEvalProfit(), balance.totalReturnRate(),
                totalAssetUsdActual, evalProfitUsdSum,
                usdDeposit, posEvalUsd, balance.exchangeRateKrwPerUsd()
        );
        return new PortfolioSummaryResponse(positions, summary);
    }
}
