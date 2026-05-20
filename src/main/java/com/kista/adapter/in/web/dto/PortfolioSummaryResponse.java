package com.kista.adapter.in.web.dto;

import com.kista.domain.model.Ticker;

import java.math.BigDecimal;
import java.util.List;

// KIS CTRP6504R 응답(PresentBalanceResult) → kista-ui 통합 포맷 DTO
// PresentBalanceResult의 camelCase 필드를 kista-ui PortfolioSnapshot 타입과 일치하도록 정규화
public record PortfolioSummaryResponse(
        List<PositionDto> positions,  // output1: 종목별 잔고 목록
        SummaryDto summary            // output3: 계좌 전체 요약
) {

    // 종목별 포지션 — kista-ui PortfolioSnapshot 호환
    public record PositionDto(
            Ticker ticker,             // pdno: 종목코드
            int qty,                   // cblc_qty13: 잔고수량
            BigDecimal avgPrice,       // avg_unpr3: 평균단가 (USD)
            BigDecimal currentPrice,   // ovrs_now_pric1: 현재가 (USD)
            BigDecimal evalAmountUsd,  // frcr_evlu_amt2: 외화평가금액
            BigDecimal profitLossUsd,  // evlu_pfls_amt2: 평가손익 (USD)
            BigDecimal profitRate,     // evlu_pfls_rt1: 평가손익률 %
            String exchangeCode        // ovrs_excg_cd: 해외거래소코드
    ) {}

    // 계좌 전체 요약 — PresentBalanceResult output3 필드
    public record SummaryDto(
            BigDecimal totalAssetUsd,    // tot_asst_amt: 총자산 (USD)
            BigDecimal totalEvalProfit,  // tot_evlu_pfls_amt: 총평가손익 (USD)
            BigDecimal totalReturnRate   // evlu_erng_rt1: 총수익률 %
    ) {}
}
