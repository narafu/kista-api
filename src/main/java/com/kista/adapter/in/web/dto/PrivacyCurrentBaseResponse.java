package com.kista.adapter.in.web.dto;

import com.kista.domain.model.privacy.PrivacyCurrentBase;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

// PRIVACY 전략 매수 배수 MAX 계산용 기준가 응답
public record PrivacyCurrentBaseResponse(
        @Schema(description = "대상 종목 코드", example = "SOXL")
        String ticker,
        @Schema(description = "현재 사이클 시작 기준가 ($)", example = "25.4300")
        BigDecimal currentCycleStart,
        @Schema(description = "기준 매매표 거래일", example = "2026-05-26")
        LocalDate tradeDate
) {
    public static PrivacyCurrentBaseResponse from(PrivacyCurrentBase domain) {
        return new PrivacyCurrentBaseResponse(
                domain.ticker().name(),
                domain.currentCycleStart(),
                domain.tradeDate()
        );
    }
}
